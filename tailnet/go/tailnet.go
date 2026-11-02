// Package tailnet embeds a Tailscale node (tsnet, userspace WireGuard over a
// gVisor stack) behind a loopback SOCKS5 proxy: the Kotlin side points every
// OkHttp client at `Proxy(SOCKS, 127.0.0.1:SocksPort())` and every Hermes /
// watchkit call — REST, SSE, WebSocket — rides the tailnet. This is SPEC
// §11.4 option (a); no VpnService, no second APK, MagicDNS resolves inside
// the tunnel (SOCKS CONNECT carries hostnames to tsnet verbatim).
//
// gomobile surface rules honored: no variadic args, no maps, ctx never
// crosses the boundary, errors become Java exceptions. All state is
// package-global — the app runs exactly one node ("hermes-wearos-*").
//
// All traffic is TCP today (REST + SSE + WebSocket-over-TCP), so the proxy
// implements CONNECT-only SOCKS5 with no UDP associate.
package tailnet

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"

	_ "tailscale.com/feature/androiddns" // dnsproxyd resolver: no /etc/resolv.conf on Android (auto-registers its hook)
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

const logRingMax = 240

var (
	mu        sync.Mutex
	srv       *tsnet.Server
	socksLn   net.Listener
	socksPort int
	authURL   string
	logRing   []string
)

// authURLRe matches the login link tsnet prints through UserLogf when the
// node needs interactive enrollment ("To authenticate, visit: https://…").
var authURLRe = regexp.MustCompile(`https://login\.tailscale\.com/a/\S+`)

// ── Android app-sandbox survival (tailscale#2293, golang/go#40569) ─────────
// Android denies app UIDs the NETLINK_ROUTE socket, so Go's net.Interfaces
// always fails and tsnet dies at Start with "route ip+net: netlinkrib:
// permission denied". tailscale's own answer is feature/androidbin — but its
// auto-registration is excluded for `android && cgo` builds (gomobile!),
// because the official app feeds interface state from Java instead. We are
// gomobile with no Java feed, so we register the same synthetic-interface
// fallback the Termux/raw-binary path uses: one made-up interface carrying
// the source addresses the kernel picks for outbound UDP sockets (permitted
// under the sandbox; sends nothing). androiddns (imported above) covers the
// matching resolv.conf gap for name resolution.
func init() {
	if onAndroidSandbox() {
		netmon.RegisterInterfaceGetter(syntheticInterfaces)
	}
}

// onAndroidSandbox mirrors androidbin.onAndroid: true for our gomobile
// binaries, and for GOOS=linux binaries under an Android kernel (the host
// smoketest on desktop Linux stays false, keeping real interface data).
func onAndroidSandbox() bool {
	if runtime.GOOS == "android" {
		return true
	}
	_, err := os.Stat("/dev/__properties__")
	return err == nil
}

func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	for _, probe := range []struct{ net, addr string }{
		{"udp4", "8.8.8.8:53"}, {"udp6", "[2001:4860:4860::8888]:53"},
	} {
		if ip, ok := outboundIP(probe.net, probe.addr); ok {
			bits := 32
			if probe.net == "udp6" {
				bits = 128
			}
			addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(bits, bits)})
		}
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("tailnet(android): no outbound routes found")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
		AltAddrs:  addrs,
	}}, nil
}

// outboundIP asks the kernel which source address it picks for an outbound
// UDP socket — route selection without sending a packet, permitted where
// interface enumeration is not.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}

func ringLogf(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	mu.Lock()
	logRing = append(logRing, line)
	if len(logRing) > logRingMax {
		logRing = logRing[len(logRing)-logRingMax:]
	}
	mu.Unlock()
}

func userLogf(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	if m := authURLRe.FindString(line); m != "" {
		mu.Lock()
		authURL = m
		mu.Unlock()
	}
	ringLogf("%s", "[user] "+line)
}

// Start boots the node: state lives under dir (created; keep it in app
// filesDir so identity survives reboots and upgrades). authKey enrolls a
// fresh node — ignored once enrolled, exactly like tailscale(1); pass
// forceLogin=true to drop local state and re-enroll (Settings ▸ Tailnet ▸
// Re-enroll, and the key-rotation path). Returns the SOCKS5 port.
//
// Does NOT wait for "Running": first enrollment waits on a human or key, and
// Kotlin owns the wait via StatusJSON(). Safe to call twice — second call is
// a no-op returning the live port.
func Start(dir string, hostname string, authKey string, forceLogin bool) (int, error) {
	ringLogf("node starting (host=%s)", hostname)
	mu.Lock()
	if srv != nil {
		p := socksPort
		mu.Unlock()
		return p, nil
	}
	mu.Unlock()

	if err := os.MkdirAll(dir, 0700); err != nil {
		return 0, fmt.Errorf("tailnet dir: %w", err)
	}
	// Android app sandbox: logpolicy's fallback chain ends at a /tmp-style temp
	// dir that does not exist here, and it PANICS in an internal goroutine
	// ("no safe place found to store log state") — fatal under gomobile, caught
	// on the emulator 2026-11-02. TS_LOGS_DIR only counts if the directory
	// ALREADY EXISTS (LogsDir os.Stats it, never creates), so create it first.
	// Also opt out of log.tailscale.com log sharing (the library default): no log
	// upload from a personal watch node, one fewer network path to fail.
	if logsDir := filepath.Join(dir, "logs"); os.MkdirAll(logsDir, 0700) == nil {
		os.Setenv("TS_LOGS_DIR", logsDir)
	}
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "1")
	if forceLogin {
		if err := WipeState(dir); err != nil {
			return 0, err
		}
	}

	s := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		AuthKey:  authKey,
		UserLogf: userLogf,
		Logf:     ringLogf,
	}
	if err := s.Start(); err != nil {
		return 0, fmt.Errorf("tsnet start: %w", err)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.Close()
		return 0, fmt.Errorf("socks listen: %w", err)
	}

	mu.Lock()
	srv = s
	socksLn = ln
	socksPort = ln.Addr().(*net.TCPAddr).Port
	authURL = ""
	mu.Unlock()

	ringLogf("socks5 listening on 127.0.0.1:%d", socksPort)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return // closed by Stop
			}
			go handleSocks(c)
		}
	}()
	// Up() drives enrollment + reconnect; its exit is NOT fatal (logged-out
	// waits on auth, network drops retry internally) — it only feeds logs.
	go func() {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		if _, err := s.Up(ctx); err != nil {
			ringLogf("up: %v", err)
		}
	}()
	return socksPort, nil
}

// SocksPort returns the live SOCKS5 loopback port (0 = not started).
func SocksPort() int {
	mu.Lock()
	defer mu.Unlock()
	return socksPort
}

// WipeState deletes the node's on-device identity state so the next Start
// re-enrolls with its authKey. Caller owns closing a running node first.
// A missing state file is success — re-enroll on a fresh install means the
// same thing as re-enroll on a stale node: "enroll fresh".
func WipeState(dir string) error {
	err := os.Remove(filepath.Join(dir, "tailscaled.state"))
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("wipe state: %w", err)
	}
	return nil
}

// AuthURL returns the interactive login link if the node is waiting for a
// human to enroll it ("" otherwise). On a watch the link is a last resort —
// the authKey path is the design — but it is the honest answer to "why is
// this node stuck".
func AuthURL() string {
	mu.Lock()
	defer mu.Unlock()
	return authURL
}

// Stop shuts the node down and frees the port. State on disk is kept — this
// is a pause, not an unenroll.
func Stop() error {
	mu.Lock()
	s, ln := srv, socksLn
	srv, socksLn, socksPort = nil, nil, 0
	mu.Unlock()
	if ln != nil {
		ln.Close()
	}
	if s != nil {
		return s.Close()
	}
	return nil
}

// StatusJSON is the Kotlin-visible truth: backendState + self identity +
// peer onlineness + authURL + socks port. Shape is ours (compact; the watch
// UI renders it and domain/TailnetState.kt parses it — keep keys stable).
func StatusJSON() (string, error) {
	mu.Lock()
	s, port, au := srv, socksPort, authURL
	mu.Unlock()
	out := map[string]any{
		"backendState": "stopped",
		"socksPort":    port,
		"authURL":      au,
	}
	if s == nil {
		b, _ := json.Marshal(out)
		return string(b), nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		out["error"] = err.Error()
		b, _ := json.Marshal(out)
		return string(b), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		out["error"] = err.Error()
		b, _ := json.Marshal(out)
		return string(b), nil
	}
	out["backendState"] = string(st.BackendState)
	if st.Self != nil {
		out["self"] = map[string]any{
			"hostname": st.Self.HostName,
			"dnsName":  strings.TrimSuffix(st.Self.DNSName, "."),
			"ips":      st.Self.TailscaleIPs,
			"online":   st.Self.Online,
		}
	}
	var peers []map[string]any
	for _, p := range st.Peer {
		var ip string
		if len(p.TailscaleIPs) > 0 {
			ip = p.TailscaleIPs[0].String()
		}
		peers = append(peers, map[string]any{
			"hostname": p.HostName,
			"dnsName":  strings.TrimSuffix(p.DNSName, "."),
			"ip":       ip,
			"online":   p.Online,
			"relay":    p.Relay,
		})
	}
	out["peers"] = peers
	b, err := json.Marshal(out)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// LogsJSON returns the tailnet engine's recent log lines (ring buffer) for
// logcat/Settings — the tsnet equivalent of `tailscale status` debug output.
func LogsJSON() (string, error) {
	mu.Lock()
	lines := append([]string{}, logRing...)
	mu.Unlock()
	b, err := json.Marshal(lines)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// ── SOCKS5 (CONNECT only) ────────────────────────────────────────────────
// The Kotlin/OkHttp side never sees this protocol; it lives here so plain
// Proxy(SOCKS) works with zero custom SocketFactory plumbing. Hostnames are
// passed to tsnet.Dial VERBATIM — that is what makes .ts.net names resolve.

func handleSocks(c net.Conn) {
	defer c.Close()
	if err := socksHandshake(c); err != nil {
		ringLogf("socks: %v", err)
		return
	}
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return
	}
	// No deadline on the stream: SSE streams idle within server keepalives,
	// and either peer's close ends the copy.
	hostport, err := socksReadTarget(c)
	if err != nil {
		ringLogf("socks target: %v", err)
		return
	}
	dctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	dc, err := s.Dial(dctx, "tcp", hostport)
	cancel()
	if err != nil {
		ringLogf("socks dial %s: %v", hostport, err)
		socksReply(c, errToSOCKS(err))
		return
	}
	defer dc.Close()
	socksReply(c, 0)
	stream(c, dc)
}

func socksHandshake(c net.Conn) error {
	c.SetReadDeadline(time.Now().Add(15 * time.Second))
	defer c.SetReadDeadline(time.Time{})
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return err
	}
	if hdr[0] != 5 {
		return fmt.Errorf("socks version %d (want 5)", hdr[0])
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	// Accept NO AUTH (0); OkHttp's SOCKS default sends exactly that.
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return err
	}
	return nil
}

// socksReadTarget parses the CONNECT request and returns host:port.
func socksReadTarget(c net.Conn) (string, error) {
	req := make([]byte, 5)
	if _, err := io.ReadFull(c, req); err != nil {
		return "", err
	}
	if req[0] != 5 {
		return "", fmt.Errorf("socks ver %d", req[0])
	}
	if req[1] != 1 { // CONNECT
		return "", fmt.Errorf("socks cmd %d (want CONNECT)", req[1])
	}
	var host string
	switch req[3] {
	case 1: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3: // DOMAIN — resolved INSIDE the tailnet (MagicDNS truth)
		domLen := make([]byte, 1)
		if _, err := io.ReadFull(c, domLen); err != nil {
			return "", err
		}
		dom := make([]byte, domLen[0])
		if _, err := io.ReadFull(c, dom); err != nil {
			return "", err
		}
		host = string(dom)
	case 4: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(c, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	default:
		return "", fmt.Errorf("socks atyp %d", req[3])
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(c, pb); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, fmt.Sprint(int(pb[0])<<8|int(pb[1]))), nil
}

func socksReply(c net.Conn, rep byte) {
	c.Write([]byte{5, rep, 0, 1, 0, 0, 0, 0, 0, 0})
}

func errToSOCKS(err error) byte {
	s := err.Error()
	switch {
	case strings.Contains(s, "no such host") || strings.Contains(s, "not found"):
		return 4 // host unreachable
	case strings.Contains(s, "timeout") || strings.Contains(s, "no route") || strings.Contains(s, "rejected"):
		return 4
	default:
		return 1 // general failure
	}
}

// stream copies both directions until either side closes.
func stream(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); io.Copy(b, a); closeWrite(b) }()
	go func() { defer wg.Done(); io.Copy(a, b); closeWrite(a) }()
	wg.Wait()
}

// closeWrite half-closes so the other io.Copy terminates (SSE server close
// must reach OkHttp as a clean end-of-stream, not a socket RST).
func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
		return
	}
	c.Close()
}

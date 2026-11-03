// smoketest: host-side proof of the shim (no Android needed) — boots the
// node, waits for Running, then dials target THROUGH the SOCKS5 port and
// prints the HTTP reply. This is Milestone-1's acceptance gate:
//
//   TS_AUTHKEY=… TARGET=labhost.tailca7ee1.ts.net:8642 ./smoketest
//
// The dial must succeed with the HOST's own tailnet route irrelevant: we
// resolve+connect via the SOCKS port, and the CONNECT hostname is resolved by
// tsnet's netstack, not the OS resolver.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"hermes.dev/tailnet"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	authKey := os.Getenv("TS_AUTHKEY")
	target := os.Getenv("TARGET")
	if target == "" {
		target = "labhost.tailca7ee1.ts.net:8642"
	}
	dir := filepath.Join(os.TempDir(), "hermes-tailnet-smoke")
	hostname := os.Getenv("HOSTNAME_TS")
	if hostname == "" {
		hostname = "hermes-wearos-smoke"
	}

	port, err := tailnet.Start(dir, hostname, authKey, os.Getenv("FORCE_LOGIN") == "1")
	must(err)
	fmt.Printf("socks5=127.0.0.1:%d\n", port)

	deadline := time.Now().Add(90 * time.Second)
	var self map[string]any
	for time.Now().Before(deadline) {
		sj, err := tailnet.StatusJSON()
		must(err)
		var st map[string]any
		must(json.Unmarshal([]byte(sj), &st))
		if st["backendState"] == "Running" {
			self, _ = st["self"].(map[string]any)
			break
		}
		if au, _ := st["authURL"].(string); au != "" {
			fmt.Println("authURL:", au)
		}
		time.Sleep(1 * time.Second)
	}
	if self == nil {
		logs, _ := tailnet.LogsJSON()
		fmt.Println("TIMEOUT waiting for Running. last logs:", logs)
		os.Exit(1)
	}
	fmt.Printf("node up: %v\n", self)

	// HTTP-over-SOCKS5: proxy Dial is our own tiny client to keep deps out.
	conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	must(err)
	// greeting: ver5, 1 method, NO_AUTH
	must(conn.SetWriteDeadline(time.Now().Add(10 * time.Second)))
	conn.Write([]byte{5, 1, 0})
	resp := make([]byte, 2)
	must(readFull(conn, resp))
	// connect request: ver, cmd=1, rsv, atyp=3(domain), len, domain, port 8642
	host, portNum := target[:strings.IndexByte(target, ':')], target[strings.IndexByte(target, ':')+1:]
	var p int
	fmt.Sscanf(portNum, "%d", &p)
	req := []byte{5, 1, 0, 3, byte(len(host))}
	req = append(req, host...)
	req = append(req, byte(p>>8), byte(p))
	conn.Write(req)
	rbuf := make([]byte, 10)
	must(readFull(conn, rbuf))
	if rbuf[1] != 0 {
		fmt.Printf("SOCKS CONNECT failed, reply=%d\n", rbuf[1])
		os.Exit(1)
	}
	fmt.Fprint(conn, "GET /health HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n")
	// The relay is deadline-free BY DESIGN (SSE idles within server
	// keepalives) — so the TEST owns its own read deadline.
	conn.SetReadDeadline(time.Now().Add(20 * time.Second))
	sc := bufio.NewScanner(conn)
	for sc.Scan() {
		fmt.Println(">", sc.Text())
		if strings.Contains(sc.Text(), "hermes-agent") {
			fmt.Println("SMOKETEST PASS: gateway health through embedded tailnet")
			break
		}
	}
	ctx := context.Background()
	_ = ctx
	tailnet.Stop()
}

func readFull(c net.Conn, b []byte) error {
	_, err := read(c, b)
	return err
}

func read(c net.Conn, b []byte) (int, error) {
	total := 0
	for total < len(b) {
		n, err := c.Read(b[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

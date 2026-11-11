# hermes-wearos:voiceout — the voice-out demo/proving image.
#
# Base: hermes-film:dev (upstream hermes-agent + Xvfb/ffmpeg/tesseract film box;
# it builds the whole runtime and supervises the gateway). This layer adds the
# Wear Kit plugin FROM THIS BRANCH (voice-out slices 1–2c) plus a demo lane
# that speaks the /voice/* surface with a fake whois (no tailscale needed).
#
#   docker build -f docker/wearos-demo/Dockerfile -t hermes-wearos:voiceout .
#   docker run --rm hermes-wearos:voiceout bash -c '/opt/wearos/demo/run-demo.sh'
#   docker run --rm hermes-wearos:voiceout /opt/hermes/.venv/bin/python -m pytest /opt/wearos/plugin/tests -q
#
# Real speech: consent to the Neuphonic gated repos, then
#   echo <read-token> > tok && docker run --rm -v $PWD/tok:/tok:ro \
#     -e HF_TOKEN_FILE=/tok hermes-wearos:voiceout /opt/wearos/install-tts.sh
# (HF_TOKEN_FILE also picks up the standard ~/.cache/huggingface/token layout.)
FROM hermes-film:dev

COPY hermes-plugin/ /opt/wearos/plugin/
COPY docker/wearos-demo/seed-plugin.sh /etc/cont-init.d/03-wearos-plugin
COPY docker/wearos-demo/install-tts.sh /opt/wearos/install-tts.sh
COPY docker/wearos-demo/demo_lane.py /opt/wearos/demo/demo_lane.py
COPY docker/wearos-demo/run-demo.sh /opt/wearos/demo/run-demo.sh

# test/dev deps: the agent venv is uv-made (no pip) — unpack pure-python wheels
# into /opt/wearos/pylibs and put that on PYTHONPATH for the suite runner.
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends python3-pip \
 && /usr/bin/python3 -m pip install --quiet --no-cache-dir \
      --break-system-packages --target /opt/wearos/pylibs pytest pygments \
 && chmod +x /etc/cont-init.d/03-wearos-plugin /opt/wearos/install-tts.sh \
             /opt/wearos/demo/run-demo.sh /opt/wearos/demo/demo_lane.py \
 && apt-get clean

ENV HF_TOKEN_FILE=/run/secrets/hf_token

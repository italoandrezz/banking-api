# Official nginx 1.30.6 without unused image/XML processing modules.
FROM nginx@sha256:3028fc1419f99b95c1c69f844bbaf49dc1b881750c51b497fc721ac4f02a14e6
# CVE-2026-85091: require the patched Alpine revision, fail if unavailable.
RUN apk add --no-cache 'zlib>=1.3.2-r1'
USER 101:101

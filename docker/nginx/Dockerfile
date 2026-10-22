# Official nginx 1.30.6 Alpine 3.24 slim; musl 1.2.6-r3 fixes CVE-2026-107891.
FROM nginx@sha256:d9322bba63c6cfc63dc9e86522fd4f95fd4d9020a02b5bced282bbf38b8c3960
# CVE-2026-85091: require the patched Alpine revision, fail if unavailable.
RUN apk add --no-cache 'zlib>=1.3.2-r1'
USER 101:101

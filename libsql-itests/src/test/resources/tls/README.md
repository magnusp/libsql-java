# TLS fixtures for the `wss://` integration tests

`server.crt.pem`, `server.key.pem`, and `truststore.p12` belong to a self-signed
certificate for `localhost` and `127.0.0.1` that exists only so the test suite can
put a TLS terminator in front of sqld. The key is public and worthless.

Regenerate them when they expire (2036-10-21) with:

```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout server.key.pem -out server.crt.pem \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
openssl pkcs12 -export -in server.crt.pem -inkey server.key.pem \
  -name libsql-test-tls -out truststore.p12 -passout pass:changeit
```

`libsql-itests/pom.xml` points the failsafe JVM at `truststore.p12` through
`javax.net.ssl.trustStore`, so the driver trusts this certificate and nothing else
during the integration tests.

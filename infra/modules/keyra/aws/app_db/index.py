"""Creates one app database and its owner role on RDS, or resets the role's password.

RDS has no API for databases or roles, so Terraform invokes this from inside the VPC. The event names
secrets, never passwords (invocation input is kept in Terraform state). Uses only the standard library
and boto3 (both in the Lambda runtime): a minimal Postgres client with TLS and SCRAM-SHA-256.
"""

import base64
import hashlib
import hmac
import os
import re
import secrets
import socket
import ssl
import struct

import boto3

CA_BUNDLE = os.path.join(os.path.dirname(__file__), "rds-ca-bundle.pem")
NAME = re.compile(r"^[a-z][a-z0-9_]{0,62}$")


def handler(event, _context):
    name = event["database"]
    if not NAME.match(name) or name in ("keyra", "postgres") or name.startswith("rds"):
        raise ValueError(f"invalid database name: {name}")

    sm = boto3.client("secretsmanager")
    master_password = sm.get_secret_value(SecretId=event["master_secret_arn"])["SecretString"]
    app_password = sm.get_secret_value(SecretId=event["app_secret_arn"])["SecretString"]

    pg = Postgres(event["host"], int(event["port"]), event["master_user"], master_password, "postgres")
    try:
        # A SCRAM verifier, not the password, so the plaintext never reaches the server or its logs.
        verifier = scram_verifier(app_password)
        if pg.query(f"SELECT 1 FROM pg_roles WHERE rolname = '{name}'"):
            pg.query(f"ALTER ROLE \"{name}\" WITH LOGIN PASSWORD '{verifier}'")
        else:
            pg.query(f"CREATE ROLE \"{name}\" LOGIN PASSWORD '{verifier}'")
        if not pg.query(f"SELECT 1 FROM pg_database WHERE datname = '{name}'"):
            # The master (Keyra's own user) may only create a database owned by a role it is a member of.
            # The membership is temporary and, on 16+, inherits nothing, so Keyra's user keeps no access to app data.
            master = event["master_user"]
            inherit = " WITH INHERIT FALSE" if int(pg.query("SHOW server_version_num")[0]) >= 160000 else ""
            pg.query(f"GRANT \"{name}\" TO \"{master}\"{inherit}")
            try:
                pg.query(f"CREATE DATABASE \"{name}\" OWNER \"{name}\"")
                # Only the owner can take away PUBLIC's default CONNECT and TEMP.
                pg.query(f"SET ROLE \"{name}\"")
                pg.query(f"REVOKE ALL ON DATABASE \"{name}\" FROM PUBLIC")
            finally:
                pg.query("RESET ROLE")
                pg.query(f"REVOKE \"{name}\" FROM \"{master}\"")
    finally:
        pg.close()
    return {"database": name}


def scram_verifier(password, iterations=4096):
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    stored_key = hashlib.sha256(_hmac(salted, b"Client Key")).digest()
    server_key = _hmac(salted, b"Server Key")
    return f"SCRAM-SHA-256${iterations}:{_b64(salt)}${_b64(stored_key)}:{_b64(server_key)}"


class Postgres:
    """Protocol 3.0 over TLS (verified against the RDS CA), SCRAM-SHA-256 auth, simple queries only."""

    def __init__(self, host, port, user, password, database):
        raw = socket.create_connection((host, port), timeout=15)
        raw.sendall(struct.pack("!ii", 8, 80877103))  # SSLRequest
        if raw.recv(1) != b"S":
            raw.close()
            raise ConnectionError("server refused TLS")
        context = ssl.create_default_context(cafile=CA_BUNDLE)
        self.sock = context.wrap_socket(raw, server_hostname=host)
        params = f"user\0{user}\0database\0{database}\0\0".encode()
        self.sock.sendall(struct.pack("!ii", 8 + len(params), 196608) + params)
        self._authenticate(password)

    def _authenticate(self, password):
        nonce = _b64(secrets.token_bytes(18))
        client_first_bare = f"n=,r={nonce}"
        server_signature = None
        while True:
            kind, body = self._read()
            if kind == b"R":
                code = struct.unpack("!i", body[:4])[0]
                if code == 10:  # SASL: start SCRAM
                    if b"SCRAM-SHA-256\0" not in body[4:]:
                        raise ConnectionError("server does not offer SCRAM-SHA-256")
                    first = f"n,,{client_first_bare}".encode()
                    self._send(b"p", b"SCRAM-SHA-256\0" + struct.pack("!i", len(first)) + first)
                elif code == 11:  # SASLContinue: prove the password
                    server_first = body[4:].decode()
                    attrs = dict(part.split("=", 1) for part in server_first.split(","))
                    if not attrs["r"].startswith(nonce):
                        raise ConnectionError("SCRAM nonce mismatch")
                    salted = hashlib.pbkdf2_hmac("sha256", password.encode(), base64.b64decode(attrs["s"]), int(attrs["i"]))
                    client_key = _hmac(salted, b"Client Key")
                    final_bare = f"c=biws,r={attrs['r']}"
                    auth_message = f"{client_first_bare},{server_first},{final_bare}".encode()
                    signature = _hmac(hashlib.sha256(client_key).digest(), auth_message)
                    proof = bytes(a ^ b for a, b in zip(client_key, signature))
                    server_signature = _b64(_hmac(_hmac(salted, b"Server Key"), auth_message))
                    self._send(b"p", f"{final_bare},p={_b64(proof)}".encode())
                elif code == 12:  # SASLFinal: check the server knew the password too
                    if body[4:].decode() != f"v={server_signature}":
                        raise ConnectionError("SCRAM server signature mismatch")
                elif code != 0:
                    raise ConnectionError(f"unsupported authentication method {code}")
            elif kind == b"E":
                raise ConnectionError(_error(body))
            elif kind == b"Z":
                return

    def query(self, sql):
        """Runs one statement; returns the first column of each row."""
        self._send(b"Q", sql.encode() + b"\0")
        rows, error = [], None
        while True:
            kind, body = self._read()
            if kind == b"D":
                length = struct.unpack("!i", body[2:6])[0]
                rows.append(None if length < 0 else body[6 : 6 + length].decode())
            elif kind == b"E":
                error = _error(body)
            elif kind == b"Z":
                if error:
                    raise RuntimeError(error)
                return rows

    def close(self):
        try:
            self._send(b"X", b"")
        finally:
            self.sock.close()

    def _send(self, kind, body):
        self.sock.sendall(kind + struct.pack("!i", len(body) + 4) + body)

    def _read(self):
        header = self._exactly(5)
        return header[:1], self._exactly(struct.unpack("!i", header[1:])[0] - 4)

    def _exactly(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("connection closed")
            data += chunk
        return data


def _error(body):
    fields = dict((f[:1], f[1:].decode(errors="replace")) for f in body.split(b"\0") if f)
    return f"{fields.get(b'C', '')} {fields.get(b'M', 'error')}"


def _hmac(key, message):
    return hmac.new(key, message, hashlib.sha256).digest()


def _b64(data):
    return base64.b64encode(data).decode()

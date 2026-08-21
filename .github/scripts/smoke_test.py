#!/usr/bin/env python3
"""End-to-end check against a packaged redis-in-java jar.

Boots the jar the way the README tells a user to, drives it over a real socket,
then kills it and boots it again to confirm the AOF replayed. Exits non-zero on
the first failed expectation.

Usage:
    python .github/scripts/smoke_test.py target/redis-1.0-SNAPSHOT.jar [port]
"""

import os
import socket
import subprocess
import sys
import tempfile
import time

FAILURES = []
CHECKS = 0


def check(label, actual, expected):
    global CHECKS
    CHECKS += 1
    if actual == expected:
        print("  ok   {}".format(label))
    else:
        print("  FAIL {}\n         expected: {!r}\n         actual:   {!r}".format(
            label, expected, actual))
        FAILURES.append(label)


class Resp:
    """A RESP client that reads replies strictly, so a wrong $N shows up here."""

    def __init__(self, host, port, timeout=10):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.buf = b""

    def send(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            if isinstance(a, str):
                a = a.encode("utf-8")
            out += b"$%d\r\n" % len(a) + a + b"\r\n"
        self.sock.sendall(out)

    def _fill(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("server closed the connection")
            self.buf += chunk

    def _line(self):
        while b"\r\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("server closed the connection")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def read(self):
        """Returns ('+'|'-'|':'|'$'|'*', payload)."""
        line = self._line()
        kind, rest = line[:1], line[1:]
        if kind in (b"+", b"-", b":"):
            return kind.decode(), rest
        if kind == b"$":
            length = int(rest)
            if length == -1:
                return "$", None
            self._fill(length + 2)
            payload, self.buf = self.buf[:length], self.buf[length + 2:]
            return "$", payload
        if kind == b"*":
            count = int(rest)
            return "*", [self.read()[1] for _ in range(count)]
        raise AssertionError("unknown reply prefix {!r}".format(kind))

    def call(self, *args):
        self.send(*args)
        return self.read()

    def at_eof(self):
        self.sock.settimeout(5)
        try:
            return self.sock.recv(1) == b""
        except (socket.timeout, OSError):
            return False

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def start_server(jar, port, aof_path):
    env = dict(os.environ)
    env.update({
        "REDIS_PORT": str(port),
        "REDIS_AOF_ENABLED": "true",
        "REDIS_AOF_PATH": aof_path,
        # ALWAYS means every append is fsynced, so the hard kill below must not
        # lose anything. A graceful shutdown would not prove that.
        "REDIS_AOF_FSYNC": "ALWAYS",
    })
    proc = subprocess.Popen(
        ["java", "-jar", jar],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    deadline = time.time() + 60
    while time.time() < deadline:
        if proc.poll() is not None:
            print(proc.stdout.read().decode(errors="replace"))
            raise SystemExit("server exited during startup with code {}".format(proc.returncode))
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return proc
        except OSError:
            time.sleep(0.25)
    proc.kill()
    raise SystemExit("server did not start listening on port {}".format(port))


def stop_server(proc):
    proc.kill()
    proc.wait(timeout=30)


UTF8_VALUE = "Olá, José ☕ \U0001f680"
BINARY_VALUE = bytes(range(256))


def first_boot(port):
    print("protocol")
    c = Resp("127.0.0.1", port)

    check("PING", c.call("PING"), ("+", b"PONG"))
    check("ECHO", c.call("ECHO", "hello world"), ("$", b"hello world"))

    print("binary safety")
    c.call("SET", "utf8", UTF8_VALUE)
    check("UTF-8 round trip", c.call("GET", "utf8"), ("$", UTF8_VALUE.encode("utf-8")))
    check("STRLEN is a byte count",
          c.call("STRLEN", "utf8"), (":", str(len(UTF8_VALUE.encode("utf-8"))).encode()))

    c.call("SET", "binary", BINARY_VALUE)
    check("binary round trip (embedded CR/LF/NUL)", c.call("GET", "binary"), ("$", BINARY_VALUE))
    check("stream still aligned after binary", c.call("PING"), ("+", b"PONG"))

    print("conditional writes")
    c.call("SET", "cond", "first")
    check("SET NX on an existing key", c.call("SET", "cond", "second", "NX"), ("$", None))
    check("value unchanged by failed NX", c.call("GET", "cond"), ("$", b"first"))
    check("SET XX on a missing key", c.call("SET", "absent", "x", "XX"), ("$", None))
    check("SETNX first caller", c.call("SETNX", "lock", "1"), (":", b"1"))
    check("SETNX second caller", c.call("SETNX", "lock", "2"), (":", b"0"))

    print("counters")
    check("INCR", c.call("INCR", "ctr"), (":", b"1"))
    check("INCRBY", c.call("INCRBY", "ctr", "10"), (":", b"11"))
    check("DECR", c.call("DECR", "ctr"), (":", b"10"))
    c.call("SET", "big", "9223372036854775807")
    kind, payload = c.call("INCR", "big")
    check("INCR overflow is reported", (kind, b"overflow" in payload), ("-", True))

    print("append")
    check("APPEND returns the new length", c.call("APPEND", "s", "ab"), (":", b"2"))
    c.call("APPEND", "s", "cd")
    check("APPEND accumulates", c.call("GET", "s"), ("$", b"abcd"))

    print("KEYS glob")
    for k in ("user.1", "user.2", "userX1", "a+b"):
        c.call("SET", k, "v")
    check("a dot is literal", c.call("KEYS", "user.1"), ("*", [b"user.1"]))
    check("a plus is literal", c.call("KEYS", "a+b"), ("*", [b"a+b"]))
    check("character class", sorted(c.call("KEYS", "user.[12]")[1]), [b"user.1", b"user.2"])
    # An unterminated class is a class of those characters, per Redis, so the
    # result depends on the keyspace. What matters is that it replies at all
    # rather than blowing up inside a regex engine.
    check("an unterminated class does not error", c.call("KEYS", "[unclosed")[0], "*")
    check("stream still alive after odd patterns", c.call("PING"), ("+", b"PONG"))

    print("QUIT")
    check("QUIT acknowledges", c.call("QUIT"), ("+", b"OK"))
    check("QUIT closes the connection", c.at_eof(), True)
    c.close()


def second_boot(port):
    print("AOF replay after a hard kill")
    c = Resp("127.0.0.1", port)
    check("UTF-8 value survived", c.call("GET", "utf8"), ("$", UTF8_VALUE.encode("utf-8")))
    check("binary value survived", c.call("GET", "binary"), ("$", BINARY_VALUE))
    check("counter survived", c.call("GET", "ctr"), ("$", b"10"))
    check("appended value survived", c.call("GET", "s"), ("$", b"abcd"))
    check("server still responsive", c.call("PING"), ("+", b"PONG"))
    c.close()


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: smoke_test.py <path-to-jar> [port]")
    jar = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 6399

    if not os.path.exists(jar):
        raise SystemExit("jar not found: {}".format(jar))

    workdir = tempfile.mkdtemp(prefix="redis-smoke-")
    aof_path = os.path.join(workdir, "appendonly.aof")

    print("== boot 1 ==")
    proc = start_server(jar, port, aof_path)
    try:
        first_boot(port)
    finally:
        stop_server(proc)  # hard kill: no shutdown hook, no graceful flush

    print("== boot 2 ==")
    proc = start_server(jar, port, aof_path)
    try:
        second_boot(port)
    finally:
        stop_server(proc)

    print("")
    if FAILURES:
        print("{} of {} checks FAILED:".format(len(FAILURES), CHECKS))
        for f in FAILURES:
            print("  - {}".format(f))
        return 1
    print("all {} checks passed".format(CHECKS))
    return 0


if __name__ == "__main__":
    sys.exit(main())

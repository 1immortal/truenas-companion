#!/usr/bin/env python3
"""
A tiny stand-in for a TrueNAS 25.10 box, for testing the app's shell on an emulator.

It serves two endpoints on one port, like TrueNAS nginx does:
  /api/current        JSON-RPC 2.0 with just enough methods to sign in and open a shell
  /websocket/shell/   a copy of the middleware web shell handler (apps/webshell_app.py, release/25.10.3):
                      JSON {token, options} -> {"msg":"connected","id"} or {"msg":"failed"}, then raw binary
                      frames to/from a real pty, resize through core.resize_shell, socket closed when the shell exits.

Instead of `login -p -f`, `docker exec` or `incus exec` it runs /bin/sh locally (the command the client asked for
is only checked, not run). Example data only. Needs aiohttp:  pip install aiohttp

  python3 webshell_stub.py 8443 --cert cert.pem --key key.pem

WARNING: this gives whoever signs in a real shell on the machine running it. Since 1.7.1 it listens on 127.0.0.1 only
(an Android emulator reaches it as https://10.0.2.2:8443), uses a random password printed at start-up, and serves
HTTPS when --cert/--key are given (the app only signs in over HTTPS; trust the self-signed certificate once).
Tokens and terminal data are never printed. Never run it on a shared or reachable network.
"""
import asyncio, fcntl, json, os, queue, secrets, signal, struct, sys, termios, threading, time, uuid

from aiohttp import web, WSMsgType

PASSWORD = os.environ.get("STUB_PASSWORD") or secrets.token_urlsafe(12)
tokens = {}   # token -> {"origin": ip, "single_use": bool, "expires": ts, "username": str}
shells = {}   # shell id -> ShellWorker
APP_CONTAINERS = {"demo": {"3f2a9c1be0d4": {"service_name": "web"}, "8d41e7a0c9b2": {"service_name": "db"}}}


def origin_of(request):
    return request.remote


# ---------------------------------------------------------------- JSON-RPC
def rpc_result(method, params, state, request):
    if method == "auth.login_ex":
        data = params[0]
        if data.get("mechanism") == "PASSWORD_PLAIN" and data.get("password") == PASSWORD:
            state["user"] = data.get("username") or "admin"
            return {"response_type": "SUCCESS", "user_info": {"username": state["user"], "privilege": {"web_shell": True}}}
        if data.get("mechanism") == "TOKEN_PLAIN" and consume_token(data.get("token"), origin_of(request)):
            state["user"] = "admin"
            return {"response_type": "SUCCESS"}
        return {"response_type": "AUTH_ERR"}
    if method == "auth.logout":
        return True
    if not state.get("user"):
        raise RpcError(13, "ENOTAUTHENTICATED", "Not authenticated")
    if method == "auth.generate_token":
        ttl = params[0] if len(params) > 0 else 600
        match_origin = params[2] if len(params) > 2 else False
        single_use = params[3] if len(params) > 3 else False
        tok = secrets.token_urlsafe(32)
        tokens[tok] = {"origin": origin_of(request) if match_origin else None, "single_use": single_use,
                       "expires": time.time() + (ttl or 600), "username": state["user"]}
        print(f"auth.generate_token ttl={ttl} match_origin={match_origin} single_use={single_use}", flush=True)
        return tok
    if method == "core.resize_shell":
        sid, cols, rows = params
        w = shells.get(sid)
        if not w:
            raise RpcError(22, "EINVAL", "Shell does not exist")
        w.resize(cols, rows)
        print(f"core.resize_shell cols={cols} rows={rows}", flush=True)
        return None
    if method == "core.ping":
        return "pong"
    if method == "system.info":
        return {"version": "25.10.3", "hostname": "homenas", "physmem": 34359738368, "cores": 8, "uptime_seconds": 86400,
                "model": "Stub CPU", "system_product": "Example NAS", "timezone": "UTC", "loadavg": [0.1, 0.2, 0.3]}
    if method == "system.version":
        return "TrueNAS-25.10.3"
    if method == "app.query":
        return [{"name": "demo", "id": "demo", "state": "RUNNING", "version": "1.0.0", "human_version": "1.0.0",
                 "metadata": {"title": "Demo", "train": "community"}, "upgrade_available": False, "portals": {},
                 "active_workloads": {"containers": 2, "container_details": [
                     {"id": cid, "service_name": d["service_name"], "image": "example/" + d["service_name"] + ":1", "state": "running"}
                     for cid, d in APP_CONTAINERS["demo"].items()]}}]
    if method == "app.container_console_choices":
        return APP_CONTAINERS.get(params[0], {})
    if method == "virt.global.config":
        return {"state": "INITIALIZED", "pool": "tank"}
    if method == "virt.instance.query":
        return [{"id": "debian", "name": "debian", "type": "CONTAINER", "status": "RUNNING", "autostart": True,
                 "image": {"os": "Debian", "release": "bookworm"}, "aliases": []}]
    if method in ("service.query", "pool.query", "alert.list", "disk.query", "core.get_jobs", "vm.query", "interface.query",
                  "pool.snapshottask.query", "pool.scrub.query", "cloudsync.query", "replication.query", "sharing.smb.query",
                  "sharing.nfs.query", "pool.dataset.query"):
        return []
    if method == "core.subscribe":
        return str(uuid.uuid4())
    if method == "core.unsubscribe":
        return None
    raise RpcError(-32601, "ENOMETHOD", f"Method {method} not found")


class RpcError(Exception):
    def __init__(self, code, errname, reason):
        self.code, self.errname, self.reason = code, errname, reason


async def api_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    state = {}
    async for msg in ws:
        if msg.type != WSMsgType.TEXT:
            continue
        req = json.loads(msg.data)
        method, params, rid = req.get("method"), req.get("params") or [], req.get("id")
        try:
            body = {"jsonrpc": "2.0", "id": rid, "result": rpc_result(method, params, state, request)}
        except RpcError as e:
            body = {"jsonrpc": "2.0", "id": rid, "error": {"code": -32001 if e.code > 0 else e.code, "message": e.reason,
                    "data": {"error": e.code, "errname": e.errname, "reason": f"[{e.errname}] {e.reason}"}}}
        if method not in ("auth.login_ex", "auth.generate_token"):
            print(f"rpc {method}", flush=True)
        if rid is not None:
            await ws.send_str(json.dumps(body))
    return ws


def consume_token(tok, origin):
    t = tokens.get(tok or "")
    if not t or t["expires"] < time.time():
        return None
    if t["origin"] is not None and t["origin"] != origin:
        return None
    if t["single_use"]:
        tokens.pop(tok, None)
    return t


# ---------------------------------------------------------------- web shell (mirrors webshell_app.py)
class ShellWorker(threading.Thread):
    def __init__(self, ws, input_queue, loop):
        super().__init__(daemon=True)
        self.ws, self.input_queue, self.loop = ws, input_queue, loop
        self.shell_pid = None
        self._die = False

    def resize(self, cols, rows):
        self.input_queue.put(("resize", cols, rows))

    def run(self):
        self.shell_pid, master_fd = os.forkpty()
        if self.shell_pid == 0:
            os.chdir("/tmp")
            env = {"TERM": "xterm", "HOME": "/tmp", "LANG": "en_US.UTF-8", "PATH": "/sbin:/bin:/usr/sbin:/usr/bin", "LC_ALL": "C.UTF-8",
                   "PS1": "root@homenas:\\w# "}
            os.execve("/bin/sh", ["/bin/sh"], env)

        def reader():
            while True:
                try:
                    read = os.read(master_fd, 1024)
                except OSError:
                    break
                if read == b"":
                    break
                asyncio.run_coroutine_threadsafe(self.ws.send_bytes(read), loop=self.loop).result()

        def writer():
            while True:
                try:
                    get = self.input_queue.get(timeout=1)
                    if isinstance(get, tuple):
                        fcntl.ioctl(master_fd, termios.TIOCSWINSZ, struct.pack("HHHH", get[2], get[1], 0, 0))
                    else:
                        os.write(master_fd, get)   # a text frame (str) raises here, like in middleware
                except queue.Empty:
                    try:
                        os.kill(self.shell_pid, 0)
                    except ProcessLookupError:
                        break
                except TypeError:
                    print("text frame received after auth: middleware would abort the shell", flush=True)
                    self.abort()
                    return

        tr = threading.Thread(target=reader, daemon=True); tr.start()
        tw = threading.Thread(target=writer, daemon=True); tw.start()
        while True:
            try:
                pid, _ = os.waitpid(self.shell_pid, os.WNOHANG)
            except ChildProcessError:
                break
            if self._die:
                return
            if pid <= 0:
                time.sleep(1)
        tr.join(); tw.join()
        asyncio.run_coroutine_threadsafe(self.ws.close(), self.loop)

    def abort(self):
        asyncio.run_coroutine_threadsafe(self.ws.close(), self.loop)
        try:
            os.kill(self.shell_pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self._die = True


async def shell_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    origin = origin_of(request)
    sid = str(uuid.uuid4())
    input_queue = queue.Queue()
    authenticated = False
    worker = None
    async for msg in ws:
        if authenticated:
            input_queue.put(msg.data)
            continue
        try:
            data = json.loads(msg.data)
        except (json.decoder.JSONDecodeError, TypeError):
            continue
        token = data.get("token")
        if not token:
            continue
        if not consume_token(token, origin):
            await ws.send_json({"msg": "failed", "error": {"error": 207, "reason": "Invalid token"}})
            continue
        authenticated = True
        options = data.get("options", {})
        if options.get("app_name"):
            if not options.get("container_id") or options["container_id"] not in APP_CONTAINERS.get(options["app_name"], {}):
                print("invalid container: closing without a message", flush=True)
                break
        print(f"shell options={json.dumps(options)}", flush=True)
        worker = ShellWorker(ws, input_queue, asyncio.get_event_loop())
        worker.start()
        shells[sid] = worker
        await ws.send_json({"msg": "connected", "id": sid})
    if worker:
        worker.abort()
    shells.pop(sid, None)
    print("shell closed", flush=True)
    return ws


def main():
    import argparse, ssl
    ap = argparse.ArgumentParser(description="Local TrueNAS web shell stub (testing only)")
    ap.add_argument("port", nargs="?", type=int, default=8443)
    ap.add_argument("--cert", help="PEM certificate for HTTPS")
    ap.add_argument("--key", help="PEM private key for HTTPS")
    a = ap.parse_args()
    ctx = None
    if a.cert and a.key:
        ctx = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
        ctx.load_cert_chain(a.cert, a.key)
    app = web.Application()
    app.router.add_get("/api/current", api_handler)
    app.router.add_get("/websocket", api_handler)
    app.router.add_get("/websocket/shell/", shell_handler)
    app.router.add_get("/websocket/shell", shell_handler)
    print(f"password for this run: {PASSWORD}", file=sys.stderr, flush=True)
    scheme = "https" if ctx else "http (the app won't sign in without --cert/--key)"
    web.run_app(app, host="127.0.0.1", port=a.port, ssl_context=ctx,
                print=lambda *_: print(f"listening on 127.0.0.1:{a.port} ({scheme})", flush=True))


if __name__ == "__main__":
    main()

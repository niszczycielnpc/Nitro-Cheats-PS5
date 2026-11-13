#!/usr/bin/env python3
"""
PC relay for ps5debug-NG - only needed for the "what writes to this address" (Trace) feature of
PS5 Nitro Cheats.

The problem: after DEBUG_ATTACH the console itself connects back to the client on TCP 755 (to the
address the command came from). Android apps cannot listen on ports below 1024, so the phone cannot
accept that connection. The fix without root: the phone talks to the console THROUGH this computer.

    phone --744--> PC --744--> PS5          (commands)
    phone <-7755-- PC <--755-- PS5          (debugger interrupts)

Usage (Windows / Linux / macOS, Python 3.8+ only, no dependencies):

    python relay.py --console 192.168.101.15 --phone 192.168.101.40

In the app, enter this COMPUTER's IP as the console address (not the PS5's), put the PS5's IP into
the "etaHEN FTP address" field and leave the interrupt port at 7755.
Windows: allow Python through the firewall (private networks) the first time you run it.
Linux/macOS: ports below 1024 need root (sudo). --cmd-port can be moved higher (then use that port in
the app as well), but --irq-port has to stay 755 because the PS5 always dials 755.
"""
import argparse
import asyncio
import socket
import sys
import time


class Config:
    def __init__(self, console, phone, console_port=744, phone_irq_port=7755,
                 bind="0.0.0.0", cmd_port=744, irq_port=755, allow_any=False, quiet=False):
        self.console = console
        self.console_port = console_port
        self.phone = phone
        self.phone_irq_port = phone_irq_port
        self.bind = bind
        self.cmd_port = cmd_port
        self.irq_port = irq_port
        self.allow_any = allow_any
        self.quiet = quiet
        self.console_ip = socket.gethostbyname(console)


def log(cfg, msg):
    if not cfg.quiet:
        print(time.strftime("%H:%M:%S"), msg, flush=True)


def _nodelay(writer):
    s = writer.get_extra_info("socket")
    if s is not None:
        try:
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass


async def _pipe(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            if writer.can_write_eof():
                writer.write_eof()
        except (OSError, RuntimeError):
            pass


async def _bridge(r1, w1, r2, w2):
    """Copies both directions until one side is done, then gives the other a moment to drain."""
    _nodelay(w1)
    _nodelay(w2)
    t1 = asyncio.ensure_future(_pipe(r1, w2))
    t2 = asyncio.ensure_future(_pipe(r2, w1))
    try:
        done, pending = await asyncio.wait([t1, t2], return_when=asyncio.FIRST_COMPLETED)
        if pending:
            await asyncio.wait(pending, timeout=2)
    finally:
        for t in (t1, t2):
            if not t.done():
                t.cancel()
        for w in (w1, w2):
            try:
                w.close()
            except OSError:
                pass


async def _connect(host, port, timeout=5):
    return await asyncio.wait_for(asyncio.open_connection(host, port), timeout)


async def _handle_cmd(cfg, reader, writer):
    peer = writer.get_extra_info("peername")
    try:
        cr, cw = await _connect(cfg.console, cfg.console_port)
    except Exception as e:  # noqa: BLE001 - report any connect problem and drop the client
        log(cfg, "cannot connect to the console %s:%s: %s" % (cfg.console, cfg.console_port, e))
        writer.close()
        return
    log(cfg, "commands: %s -> console %s:%s" % (peer[0], cfg.console, cfg.console_port))
    await _bridge(reader, writer, cr, cw)


async def _handle_irq(cfg, reader, writer):
    peer = writer.get_extra_info("peername")
    if not cfg.allow_any and peer[0] != cfg.console_ip:
        log(cfg, "refused a connection on the interrupt port from %s (expected %s)" % (peer[0], cfg.console_ip))
        writer.close()
        return
    try:
        pr, pw = await _connect(cfg.phone, cfg.phone_irq_port)
    except Exception as e:  # noqa: BLE001
        log(cfg, "phone %s:%s is not accepting interrupts (%s) - start a trace in the app first" %
            (cfg.phone, cfg.phone_irq_port, e))
        writer.close()
        return
    log(cfg, "interrupts: console %s -> phone %s:%s" % (peer[0], cfg.phone, cfg.phone_irq_port))
    await _bridge(reader, writer, pr, pw)


async def start(cfg):
    """Starts both listeners and returns (cmd_server, irq_server)."""
    cmd = await asyncio.start_server(lambda r, w: _handle_cmd(cfg, r, w), cfg.bind, cfg.cmd_port)
    irq = await asyncio.start_server(lambda r, w: _handle_irq(cfg, r, w), cfg.bind, cfg.irq_port)
    return cmd, irq


def _bound_port(server):
    return server.sockets[0].getsockname()[1]


async def _main(cfg):
    try:
        cmd, irq = await start(cfg)
    except PermissionError:
        print("No permission to use ports %s/%s - run as administrator (sudo)." % (cfg.cmd_port, cfg.irq_port))
        return 1
    except OSError as e:
        print("Cannot bind ports %s/%s: %s" % (cfg.cmd_port, cfg.irq_port, e))
        return 1
    log(cfg, "ready: PC:%s -> %s:%s,  %s:%s -> phone %s:%s" % (
        _bound_port(cmd), cfg.console, cfg.console_port,
        cfg.console_ip, _bound_port(irq), cfg.phone, cfg.phone_irq_port))
    log(cfg, "in the app, enter THIS computer's address instead of the PS5's. Ctrl+C quits.")
    async with cmd, irq:
        await asyncio.gather(cmd.serve_forever(), irq.serve_forever())
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Phone <-> PC <-> PS5 relay for ps5debug (write tracing) - PS5 Nitro Cheats.")
    ap.add_argument("--console", required=True, help="IP address of the PS5")
    ap.add_argument("--phone", required=True, help="IP address of the phone")
    ap.add_argument("--console-port", type=int, default=744, help="ps5debug command port on the console (744)")
    ap.add_argument("--phone-irq-port", type=int, default=7755, help="interrupt port the app listens on (7755)")
    ap.add_argument("--bind", default="0.0.0.0", help="address to listen on at the PC")
    ap.add_argument("--cmd-port", type=int, default=744, help="command port on the PC (744)")
    ap.add_argument("--irq-port", type=int, default=755, help="interrupt port on the PC - the PS5 always connects to 755")
    ap.add_argument("--allow-any", action="store_true", help="also accept interrupts from addresses other than the PS5")
    a = ap.parse_args(argv)
    cfg = Config(a.console, a.phone, a.console_port, a.phone_irq_port, a.bind, a.cmd_port, a.irq_port, a.allow_any)
    try:
        return asyncio.run(_main(cfg))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())

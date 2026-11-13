import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay  # noqa: E402


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 20))


class RelayTest(unittest.TestCase):
    def _setup(self, console_ip="127.0.0.1"):
        async def go():
            seen = {"cmd": [], "irq": []}

            # fake console: answers PING with PONG and echoes everything else unchanged
            async def console_cmd(r, w):
                while True:
                    d = await r.read(65536)
                    if not d:
                        break
                    w.write(b"PONG" if d == b"PING" else d)
                    await w.drain()
                w.close()

            console = await asyncio.start_server(console_cmd, "127.0.0.1", 0)

            # fake phone listening for debugger interrupts
            async def phone_irq(r, w):
                d = await r.read(7)
                seen["irq"].append(d)
                w.write(b"ACK")
                await w.drain()
                w.close()

            phone = await asyncio.start_server(phone_irq, "127.0.0.1", 0)
            cfg = relay.Config("127.0.0.1", "127.0.0.1", console.sockets[0].getsockname()[1],
                               phone.sockets[0].getsockname()[1], "127.0.0.1", 0, 0, quiet=True)
            cfg.console_ip = console_ip
            cmd, irq = await relay.start(cfg)
            return seen, console, phone, cfg, cmd, irq
        return go()

    def test_commands_are_forwarded_both_ways(self):
        async def go():
            seen, console, phone, cfg, cmd, irq = await self._setup()
            r, w = await asyncio.open_connection("127.0.0.1", relay._bound_port(cmd))
            w.write(b"PING")
            await w.drain()
            self.assertEqual(b"PONG", await r.readexactly(4))
            big = bytes(range(256)) * 4096  # 1 MiB
            w.write(big)
            await w.drain()
            self.assertEqual(big, await r.readexactly(len(big)))
            w.close()
            for s in (console, phone, cmd, irq):
                s.close()
        run(go())

    def test_interrupts_reach_the_phone(self):
        async def go():
            seen, console, phone, cfg, cmd, irq = await self._setup()
            r, w = await asyncio.open_connection("127.0.0.1", relay._bound_port(irq))
            w.write(b"IRQDATA")
            await w.drain()
            self.assertEqual(b"ACK", await asyncio.wait_for(r.read(3), 5))
            self.assertEqual([b"IRQDATA"], seen["irq"])
            w.close()
            for s in (console, phone, cmd, irq):
                s.close()
        run(go())

    def test_strangers_are_refused_on_the_interrupt_port(self):
        async def go():
            seen, console, phone, cfg, cmd, irq = await self._setup(console_ip="127.0.0.2")
            r, w = await asyncio.open_connection("127.0.0.1", relay._bound_port(irq))
            w.write(b"IRQDATA")
            await w.drain()
            try:
                # closed without reaching the phone (the unread bytes make the OS answer with a reset)
                self.assertEqual(b"", await asyncio.wait_for(r.read(3), 5))
            except ConnectionResetError:
                pass
            self.assertEqual([], seen["irq"])
            w.close()
            for s in (console, phone, cmd, irq):
                s.close()
        run(go())

    def test_dead_console_closes_the_client(self):
        async def go():
            seen, console, phone, cfg, cmd, irq = await self._setup()
            console.close()
            await console.wait_closed()
            r, w = await asyncio.open_connection("127.0.0.1", relay._bound_port(cmd))
            self.assertEqual(b"", await asyncio.wait_for(r.read(1), 5))
            w.close()
            for s in (phone, cmd, irq):
                s.close()
        run(go())


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Dump the REAL raw `select` action_prompt choice objects, so a probe can pick a SPELL
instead of always taking choices[0] (which the server lists lands-first).

Read-only observation helper: answers mulligan/keep so the game starts, then prints the
first few select-choice objects verbatim.
"""
import importlib.util
import json
import os
import sys
import time
import uuid


# XMage rejects a username longer than 14 characters ("User name may not be longer than
# 14 characters"). The server reports that via SHOW_USERMESSAGE, NOT as a connect_error --
# so an over-long generated name looks like a silent create_table failure. Fail Fast here
# instead: this cost a long debug cycle on 2026-09-21.
XMAGE_MAX_USERNAME = 14


def checked_username(base, suffix=""):
    """Return base+suffix, asserting the combined length is a legal XMage username."""
    name = base + suffix
    if len(name) > XMAGE_MAX_USERNAME:
        raise ValueError(
            "username %r is %d chars; XMage rejects names longer than %d (the server "
            "reports it as SHOW_USERMESSAGE, which is easy to misread as a table failure)"
            % (name, len(name), XMAGE_MAX_USERNAME))
    return name


def is_username_rejection(msg):
    """True when a frame is the server refusing the login name."""
    if msg.get("type") == "connect_error":
        return "User name" in (msg.get("message") or "")
    if msg.get("type") == "show_usermessage":
        text = json.dumps(msg.get("messages") or msg.get("message") or "")
        return "User name" in text
    return False



HERE = os.path.dirname(os.path.abspath(__file__))
sp = importlib.util.spec_from_file_location("ta", os.path.join(HERE, "token-art-e2e.py"))
ta = importlib.util.module_from_spec(sp)
sp.loader.exec_module(ta)

DECK = "4 Krenko's Command\n4 Dragon Fodder\n4 Goblin Rally\n22 Mountain"

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8090

ws = ta.WS(HOST, PORT, origin=ta.origin_for(HOST, PORT))
ws.send({"type": "connect", "server": "127.0.0.1", "port": 17171,
         "username": "TChDump" + uuid.uuid4().hex[:6], "password": ""})
ws.drain(8, [])
ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
ws.send({"type": "create_table", "name": "tok-choicedump",
         "gameType": "Two Player Duel", "deckType": "Constructed - Freeform",
         "winsNeeded": 1, "vsAI": True, "deck": DECK,
         "spectatorsAllowed": True, "password": ""})

t = time.time()
dumped = 0
while time.time() - t < 120 and dumped < 4:
    try:
        m = ws.recv(timeout=3)
    except Exception:
        continue
    if m.get("type") != "action_prompt":
        continue
    at = m.get("action_type")
    if at == "boolean":
        ws.send({"type": "boolean_choice",
                 "value": False if m.get("context") == "mulligan" else True})
    elif at == "select":
        dumped += 1
        choices = m.get("choices") or []
        print("\n=== select #%d  context=%s can_pass=%s count=%d ===" %
              (dumped, m.get("context"), m.get("can_pass"), len(choices)), flush=True)
        for c in choices[:6]:
            print(json.dumps(c), flush=True)
        if choices:
            ws.send({"type": "select_choice", "index": choices[0].get("index")})
    elif at == "play_mana":
        ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
    elif at == "target":
        ts = m.get("targets") or []
        if m.get("required") and ts:
            ws.send({"type": "select_target", "targetId": ts[0].get("id")})
        else:
            ws.send({"type": "pass_priority"})
    else:
        ws.send({"type": "pass_priority"})
print("\ndone", flush=True)

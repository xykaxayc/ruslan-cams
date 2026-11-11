#!/usr/bin/env python3
"""Один раз положить пароль регистраторов Redline в связку ключей macOS.
Пароль вводит человек в окне со скрытым вводом; в файлах проекта он не хранится."""
import subprocess, sys

SERVICE, ACCOUNT = "ruslan-cams-redline", "admin"

script = ('display dialog "Пароль регистраторов Redline (admin):" '
          'default answer "" with hidden answer with title "ruslan-cams" '
          'buttons {"Отмена", "OK"} default button "OK"\n'
          'text returned of result')
r = subprocess.run(["osascript", "-e", script], capture_output=True, text=True)
if r.returncode != 0 or not r.stdout.strip():
    sys.exit("Отменено.")
pwd = r.stdout.rstrip("\n")
subprocess.run(["security", "add-generic-password", "-U", "-s", SERVICE, "-a", ACCOUNT, "-w", pwd],
               check=True)
print("Пароль сохранён в связке ключей:", SERVICE)

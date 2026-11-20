# Agent quick path

[简体中文](agent-quickstart.md) | English

For AI agents on a computer: follow these fixed steps to install Djini and connect the phone, without weighing options. Full details: [Install](computer-install.en.md) and [CLI](cli.en.md).

## 1. Resolve the latest release tag

```sh
curl -s https://api.github.com/repos/wangzan101/Djini/releases/latest   # read tag_name
```

```powershell
(Invoke-RestMethod https://api.github.com/repos/wangzan101/Djini/releases/latest).tag_name
```

Below, `<tag>` is that value (e.g. `v0.1.31`) and `<ver>` is the version without the `v` (e.g. `0.1.31`).

## 2. CLI or Skill

- Agent supports skills: **install the Skill by default**; it bundles the CLI script.
- Otherwise install the CLI: `pipx install 'git+https://github.com/wangzan101/Djini.git@<tag>#subdirectory=tools/djini'`.

## 3. Install the Skill

`<skills-directory>` is your agent's skills directory, as given in that agent's own documentation; the Skill goes into a `djini` folder inside it.

POSIX:

```sh
curl -L -o djini.zip https://github.com/wangzan101/Djini/archive/refs/tags/<tag>.zip
unzip -q djini.zip -d djini-src
mkdir -p <skills-directory> && cp -R djini-src/Djini-<ver>/tools/djini <skills-directory>/djini
python3 <skills-directory>/djini/scripts/mika_cli.py --version
```

Windows PowerShell:

```powershell
Invoke-WebRequest https://github.com/wangzan101/Djini/archive/refs/tags/<tag>.zip -OutFile djini.zip
Expand-Archive djini.zip -DestinationPath djini-src
New-Item -ItemType Directory -Force "<skills-directory>" | Out-Null
Copy-Item -Recurse djini-src\Djini-<ver>\tools\djini "<skills-directory>\djini"
py -3 "<skills-directory>\djini\scripts\mika_cli.py" --version
```

Interpreter: `py -3` on Windows, `python3` elsewhere. With only the Skill, `djini` below means `python3 <skills-directory>/djini/scripts/mika_cli.py` (`py -3 ...` on Windows).

## 4. Connect

Use the connection method the user picked on the phone (Settings › Connect a computer). The agent cannot see the phone: if the user has not said which one, ask once; do not guess.

1. USB: use authorized ADB.
   ```sh
   djini devices --json
   djini connect --device SERIAL --json
   ```
   With more than one phone online, ask the user which one and keep using it.
2. Wi-Fi / private VPN: use the HTTP address and token file path the user gives you (read the token from the file only; never show it):
   ```sh
   djini --url http://PHONE:8765 --token-file /path/to/token doctor --json
   ```

State `unauthorized`: ask the user to allow USB debugging on the phone. `offline`: ask them to replug the cable. Connection information unreadable (`connection_info`): ask them to unlock the phone and open Djini once, then run `connect` again.

## Export a run record

If a task is stuck or the result looks wrong, pull the redacted record (the same events as the phone's process view, without screenshot files) and line it up with `task status`:

```sh
djini task export TASK_OR_RUN_ID --json
```

The header states `task_id`, `conversation_id` and run_id. `partial: true` means it is still in progress. An unknown id is an explicit error, not an empty record.

## Notes

- Do not submit phone tasks; never print or echo the token.
- A successful `connect` / `doctor` only proves connectivity, not that a task will succeed.

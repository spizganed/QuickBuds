# Add or fix a model

Each earbud model is one line in `app/src/main/assets/models.json`. The app and the desktop apps read the same file.

## Find the data

1. Ask the owner for a report: Settings › **Test my earbuds**. The report holds the product ID, the command list and the buds' answers.
2. Read the `MODEL DATA:` line and the `CAPABILITIES:` line in the log. They show which entry the app used and which commands the buds list.
3. Find the product ID (`PRODUCT ID: 063012`) in `models.json`. If the line is missing, copy a similar model.

## The common keys

| Key | Meaning |
| --- | --- |
| `id`, `name` | The product ID (6 hex digits) and the model name. |
| `confirmed` | The rows that an owner tested and confirmed. |
| `statusQuery` | The feature IDs to ask the buds for, for example `05 06 0B 11 18 1B`. A row shows only if the buds answer. |
| `windMode` | Show the wind noise switch (feature `0x1A`). |
| `gameConfirm` | `read`: the buds apply the low latency write and never push. The app reads the state back. |
| `seqless` | `1`: the buds answer with their own sequence byte, not ours (realme Buds T100). |
| `ancModes`, `gestures`, `eqPresets`, `codecs` | Noise modes, gesture options, equalizer presets and codecs. Copy them from a model of the same family. |

[FEATURE-SYNC.md](./FEATURE-SYNC.md) explains how the app writes a setting and confirms it. [PROTOCOL.md](./PROTOCOL.md) lists the commands.

## Check your change

```bash
python3 -c "import json; json.load(open('app/src/main/assets/models.json'))"
./gradlew :app:testDebugUnitTest
```

Write in the pull request which model you tested, and with which result. Do not copy data from the HeyMelody or realme Link apps. Use only what your own earbuds answer.

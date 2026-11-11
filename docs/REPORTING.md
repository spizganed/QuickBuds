# Reports and snoop logs

## What helps the developer

**Only a Bluetooth snoop log of HeyMelody or realme Link can add or fix a feature.** The developer does not own
your earbuds. The snoop log shows the exact command that the vendor app sends. Nothing else shows it.

The model test, Settings › Feedback and the app's log only show what QuickBuds already sends. They confirm that a
feature works. They cannot show a command that QuickBuds does not know. A report with "this setting is missing" or
"this switch does nothing" and no snoop log stays open until a snoop log arrives.

**Own other buds? Please tell us what works.** In the app, Settings › Test my earbuds reconnects the buds.
It runs a tour of each feature, shows a diagnosis and adds one JSON line to the report. The desktop uses App settings.

The app offers this test once, after the first connect to a model that is not confirmed. Settings › Feedback
sends a description and the log, without a GitHub account. Or open an
[issue](https://github.com/spizganed/QuickBuds/issues). [CONTRIBUTING.md](./CONTRIBUTING.md#reporting-a-model)
says what to include. A report confirms or rules out the features that QuickBuds already has.

The developer can answer a report. The answer shows once in the app, on the next daily count (see Privacy).
To talk to the developer and other owners, join the [Discord server](https://discord.gg/pvsnEf9TrZ).
If the earbuds do not connect, follow [Send a snoop log for a connect problem](./PACKET-CAPTURE.md#send-a-snoop-log-for-a-connect-problem).

## A feature is missing or does not work

The app's log is not enough to add a missing feature. The developer does not own your earbuds and cannot guess
the command. An issue for a missing feature needs both of these:

- **A Bluetooth snoop log** of HeyMelody or realme Link while you change the setting.
- **A screen recording** of the vendor app during the same test, or numbered screenshots with a label for each step.

An issue without them waits, and closes after 7 days with no answer. The log takes about 10 minutes and needs an
Android phone. A PC cannot make it, because the vendor apps run only on a phone. The iPhone versions of the vendor
apps cannot make it either.

1. Turn on the Bluetooth HCI snoop log in Developer options.
2. Stop QuickBuds. Change the setting in the vendor app: on, off, on.
3. Take a bug report and copy `btsnoop_hci.log` out of it.
4. Open a [Missing feature issue](https://github.com/spizganed/QuickBuds/issues/new?template=missing-feature.yml) and attach the log and the recording.

All steps: [Send a snoop log for a missing feature](./PACKET-CAPTURE.md#send-a-snoop-log-for-a-missing-feature).

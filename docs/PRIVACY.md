# Privacy

Once a day, QuickBuds sends its platform, its version and a random install ID to a Cloudflare Worker.
The Worker counts active installs with it. The ID ties to no device, account or address. The app sends no earbud data.

The ID also lets the developer answer a report you sent. The answer comes back with the daily count. A problem report carries the same ID and is sent only when you send it. On Android, Settings › Count my install turns the daily count off
(then no reply can reach you). About › Privacy › Reset install ID forgets the ID. On the PC, delete the `app_id` key in `settings.json`.
The Worker code is in [report-worker/](../report-worker).

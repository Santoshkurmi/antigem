# 06 — Built-in Browser & Web Preview

AntiGem includes an integrated web browser designed specifically for testing websites, web apps, and APIs you build with the AI, without needing to switch back and forth to an external browser like Chrome.

---

## 🌐 1. Accessing the Browser

To open the browser:
- Tap the **Browser (🌐)** icon on the **Floating App Switcher**.
- Or, when the AI agent starts a local development server (like `http://localhost:3000`), tap the link or preview button in chat to open the preview instantly.

---

## 🧭 2. Browser Navigation, Address Bar & Interactive Controls

The browser interface gives you complete control over web navigation, tab management, and site data:

### Address Bar & Search
- **Empty Field Placeholder**: Displays `"Search or type URL"`.
- **Security & Connection Status**: An icon in the address bar indicates connection safety:
  - **Secure connection** (lock icon): Encrypted HTTPS connection.
  - **Local server** (computer icon): Local development server running on your phone or local network (e.g. `localhost`, `127.0.0.1`, `192.168.x.x`).
  - **Not secure** (globe icon): Unencrypted plain HTTP connection.
- **Clear**: When the address bar is focused and contains text, tap the **Clear** (X) button to erase the text instantly.
- **Reload / Stop**: When viewing a page, tap **Reload** (refresh icon) to fetch the newest version, or tap **Stop** (X icon) while a page is currently loading to abort the request.
- **Search Suggestions & History Overlay**:
  - When typing, suggestions come from two groups: **"Recent Searches"** and **"History Suggestions"**.
  - Above suggestions, a search action row offers: **Search Google for "<your text>"**.
  - If there is no previous search history, the overlay shows: *"No recent search history"*.
  - Actions on a suggestion row:
    - **Tap the row**: Immediately navigates to that query or URL.
    - **Insert** (arrow-up-left icon): Places the query into the address field for further editing without navigating immediately.
    - **Delete** (X icon): Removes that individual entry from history.
    - **Clear All**: Wipes the entire recent search history list.

### Bottom Navigation Bar
- **Back**: Navigate back to the previous webpage in the current tab's browsing history.
- **Forward**: Navigate forward to the next webpage.
- **Home**: Navigate to the new tab home screen with your speed dial shortcuts.
- **Tabs (N)**: Displays the number of open tabs. Tapping it opens the **Tabs (N)** management sheet.
- **More options**: Tap the three horizontal dots to open the browser menu.

---

## 📑 3. Tabs Panel & Speed Dial Shortcuts

### Tab Panel (`Tabs (N)`):
- Tap the tab counter button (**Tabs (N)**) to view open tabs:
  - **Header**: Shows **"Tabs (N)"** with a **Done** button to return to the active tab.
  - **Close tab**: Tap the **(X)** button on any tab tile to close that specific tab.
  - **New tab**: Tap the **New tab** button to create and open a new blank tab.
  - **Delete all**: Tap **Delete all** on the bottom left to wipe every tab.
  - **Undo Closed Tab**: Closing a single tab displays a temporary snackbar: **"Tab closed"** with an **"Undo"** button to restore it.
  - **Close All Tabs Confirmation**: Tapping **Delete all** opens the dialog:
    - Title: **Close All Tabs**
    - Message: *"Are you sure you want to close all open tabs and return to chat?"*
    - Buttons: **Close All** (confirms closing everything) and **Cancel**.

### Speed Dial Shortcuts:
- On the home screen, your pinned sites are displayed in a clean shortcut grid.
- **Add Shortcut**:
  - Tapping **Add shortcut** in the browser menu opens the **Add Shortcut** dialog:
    - **Name**: Label field with placeholder *"e.g. GitHub"*.
    - **URL**: Web address field with placeholder *"https://github.com"*.
    - Buttons: **Add** and **Cancel**.
- **Remove Shortcut**:
  - Long-pressing any shortcut on the home screen opens the **Remove Shortcut** dialog:
    - Message: *"Remove '<title>' from your home shortcuts?"*
    - Buttons: **Remove** and **Cancel**.

---

## ⚙️ 4. Browser Menu, DevTools & Desktop Mode

Tapping **More options** (three dots) opens the browser menu:

### Quick Action Tiles:
- **New tab**: Creates a new browsing tab.
- **Reload**: Refreshes the active webpage.
- **Desktop**: Toggles between mobile user-agent rendering and desktop-site mode (highlights as active when enabled).
- **DevTools**: Toggles the **Eruda** mobile developer console inside the page (highlights as active when enabled).
  - Tapping **DevTools** injects and displays the complete Eruda developer panel, giving you:
    - **Console**: Inspect JavaScript runtime errors, warnings, and network logs.
    - **Elements**: Inspect HTML elements, live CSS rules, and computed styles.
    - **Network**: Monitor HTTP request timings, headers, payloads, and API responses.
    - **Resources**: View and edit LocalStorage, SessionStorage, and cookies.

### Menu Rows:
- **Add shortcut**: Opens the **Add Shortcut** dialog to pin the current page to your home screen shortcuts.
- **Clear tab cache**: *"Reload this site's files fresh"* — Flushes local cache files for the active tab without signing you out, and displays the toast *"Tab cache cleared"*.
- **Clear browsing data**: *"Cookies, logins, history and all tabs"* — Opens the confirmation dialog:
  - Title: **Clear browsing data?**
  - Message: *"This deletes all cookies, site data (local storage, IndexedDB, service workers), cache, saved site logins, site permissions, history and closes every tab. You will be signed out of all websites, including Google accounts. Speed dial shortcuts are kept. This cannot be undone."*
  - Buttons: **Clear all** (confirms wipe and shows the toast *"Browsing data cleared"*) and **Cancel**.
- **Close browser**: Closes the browser interface and returns to your previous workspace.

---

## 🤖 5. AI Browser Automation (`Controlled by AI`)

AntiGem allows your AI agents to see, interact with, and test web pages directly through the Browser MCP tools:
- **Status Overlay Banners**: When an agent takes over the browser, a floating status pill appears at the top:
  - **"Controlled by AI"**: Displayed while the agent is connected, alongside an **"Unlock"** button that temporarily releases touch blocking and hands control back to you.
  - **"AI is working…"**: Displayed while the agent is actively navigating, clicking elements, typing input, or running assertions.
- **What the Agent Can Do**:
  - Open a web page and take full-page screenshots to inspect layout and styling.
  - Click buttons, fill form fields, and test login flows.
  - Evaluate JavaScript in the console and read DOM attributes.
  - Verify whether a bug fix or feature works as expected without manual testing.

---

## ⚠️ 6. Browser Error Cards & Diagnostics

When a page fails to load, AntiGem renders a dedicated diagnostic error card:

- **"Page Loading Stalled"**:
  - Message: *"The page is taking unusually long to respond (timeout > 15s). The local server or remote site might be unresponsive."*
- **"SSL Security Warning"**:
  - Displayed for untrusted, self-signed, or expired SSL certificates.
  - Features an **"Unlock"** button to bypass the warning and proceed anyway (dev servers on `localhost` automatically bypass self-signed SSL).
- **"Renderer Process Crashed"**:
  - Displayed if the underlying Android WebView renderer terminated unexpectedly; tap **Reload** to restart the page.
- **"Unable to Load Page"**:
  - Message: *"Could not connect to the server or display the requested URL."* or *"An unexpected network or connection error occurred."*
- **Diagnostic Logs Drawer**:
  - On any error card with telemetry, tap **"Show Diagnostic Logs (N)"** to expand recent console errors and network failures. Tap **"Hide Diagnostic Logs"** to collapse them.
  - Action buttons on error cards include **Reload**, **Search** (searches the URL query on Google), and **Dismiss**.

---

## 🛠️ Troubleshooting Browser Issues

### ❌ Symptom: The page says "Unable to Load Page" or shows "Page Loading Stalled" on localhost.
- **What to check**: Check whether your local development server is running in the terminal.
- **How to fix**:
  1. Switch to the **Terminal** or **Chat** to verify your web server (e.g. `npm run dev` or `python -m http.server`) is actually running.
  2. Check the port number printed in the terminal (for example, if it says `Local: http://localhost:5173/`, make sure you typed `5173` into the browser address bar, not `3000`).

### ❌ Symptom: The page is showing an outdated version of my code.
- **What to check**: Check if browser caching is holding onto old assets.
- **How to fix**:
  1. Open the browser menu (**More options**).
  2. Tap **Clear tab cache** (*"Reload this site's files fresh"*).
  3. If outdated cookies or stale state persist, tap **Clear browsing data** and confirm by tapping **Clear all**.

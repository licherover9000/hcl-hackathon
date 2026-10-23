# Step 06 (P5): React frontend

**Owner:** Frontend · **Status:** builds (`npm run build` OK); needs the backend running for data

## Summary
Vite + React 18 single-page app with role-based navigation and 8 screens matching the design mockups. It calls the Spring Boot API through `/api` (proxied by Vite in development, by nginx in Docker), shows every 403 / 409 / 422 reason returned by the server, and masks nothing itself: masking is done on the server.

## Files
| File | Role |
|---|---|
| `package.json`, `vite.config.js`, `index.html` | Build setup; dev server :5173 proxies `/api` to :8080 |
| `src/api.js` | Every API call, token in sessionStorage, error → ApiError(status, code, message), downloads with the token |
| `src/main.jsx` | Routes and the role guard (convenience only; the server checks again) |
| `src/Layout.jsx` | Sidebar per role, user box, logout |
| `src/pages/Login.jsx` | Sign in |
| `src/pages/Worklist.jsx` | Ranked worklist, Rules/AI toggle, queue filter for managers |
| `src/pages/Account.jsx` | Account 360: dues, why this action, EMI table with FIFO allocation, contacts and PTP status, payments, log contact, propose settlement |
| `src/pages/Settlements.jsx` | Approvals by status, approve/reject with comment, letter shown after approval |
| `src/pages/DayEnd.jsx` | Seed, import evaluation files, upload payments, run day-end, run history, downloads, outbox and letters |
| `src/pages/Dashboard.jsx` | Bucket cards, roll-rate matrix, roll rate, PTP kept, blocked contacts, settlements |
| `src/pages/Config.jsx` | Rules settings, head-only edit with reason, effective values |
| `src/pages/Audit.jsx` | Filterable audit trail, CSV export |
| `src/styles.css` | One stylesheet, colours from the mockups |

## Design decisions
- **sessionStorage for the token:** cleared when the tab closes; trade-off vs an httpOnly cookie (safer against XSS, more setup).
- **Server is the source of truth:** the UI never computes dues or rules; it shows what the API returns, so screens match the CSV outputs.
- **One API module:** every endpoint in one file, so a panelist can trace any button to its endpoint in seconds.

## Run
```
cd frontend
npm install
npm run dev        # http://localhost:5173 (backend must run on :8080)
npm run build      # production build in dist/
```

## Change scenarios
1. Remove the role check in `Protected`: a collector can open /dashboard in the browser, but the API still returns 403 and the page shows the error.
2. Send the contact time without `+05:30`: the server assumes IST, so the result is the same.
3. Stop the ML service and choose "AI order": the API returns the rules order with "AI ranking unavailable", shown as a warning.

**Driver:** [Frontend owner name]

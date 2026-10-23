# 00 · S0 · Hackathon-ready setup

## 1. Summary
This task made the template repo ready for hackathon day without building any product feature. The backend `npm test` used to print an error and exit 0, so it always looked green. Both apps now have real tests that pass. The Express server is split so tests never open a port. The README now has the user stories, assumptions and AI-usage sections the jury looks for.

## 2. Files changed
- `backend/src/app.js` (new): builds the Express app (dotenv, CORS, JSON parsing, `/api/health`, `/api` routes, error handler) and exports it.
- `backend/src/server.js` (modified): only imports `app` and calls `listen` on `PORT` (default 5000).
- `backend/tests/api.test.js` (new): supertest tests for `GET /api/health` and `GET /api/info`.
- `backend/package.json` (modified): `test` is now `jest`. Adds the dev dependencies `jest@29.7.0` and `supertest@7.0.0`, pinned exactly.
- `backend/package-lock.json` (new): created by `npm install`.
- `frontend/src/App.test.jsx` (new): smoke test that `App` renders and shows the API status, with fetch mocked.
- `frontend/package.json` (modified): `test` is now `react-scripts test --watchAll=false`. Adds `@testing-library/react@14.3.1` as a pinned dev dependency.
- `frontend/package-lock.json` (new): created by `npm install`.
- `.gitignore` (modified): ignores `prep/` and `.claude/settings.local.json`.
- `README.md` (modified): adds the User Stories table and legend, a Run Tests section, Assumptions, How We Used AI, and an updated repo tree.
- `docs/steps/00-S0-setup.md` (new): this report.
- `../CLAUDE.md` (outside the repo, in `hcl_tech/`): section 1's folder layout and Run tests lines updated. **Not committed with the repo** (see limitations).

## 3. How it works
- **Runtime:** `npm start` runs `node src/server.js`. That file calls `require('./app')`, which loads `.env` and registers middleware and routes, then `app.listen(PORT)`.
- **Backend tests:** `npm test` runs `jest`, which finds `tests/api.test.js`. That file calls `require('../src/app')`, and `request(app).get(...)` makes supertest bind the app to a temporary in-memory server. No fixed port is used and no real server needs to be running.
- **Frontend test:** `npm test` runs `react-scripts test --watchAll=false`, which finds `src/App.test.jsx`.
  - `beforeEach` replaces `global.fetch` with a mock that returns `{status:'UP'}`.
  - `render(<App/>)` triggers the `useEffect` in `App.jsx`, which calls `getApiHealth()` in `services/api.js` and then `setApiStatus('UP')`.
  - `findByText('UP')` waits for that text to appear.

## 4. Design decisions
- **Split `app.js` and `server.js`** rather than guarding `listen` with `require.main === module`. Two plain files are easier to explain. The cost is one extra file.
- **`--watchAll=false` in the frontend script** rather than `CI=true npm test`. Inline env vars behave differently in PowerShell, cmd and bash. The cost is no watch mode by default (use `npx react-scripts test` for it).
- **No `@testing-library/jest-dom`.** `findByText` already throws when the text is missing, so the extra dependency adds nothing here.
- **RTL 14.3.1** rather than 16.x. Version 14 supports React 18 and the Jest 27 bundled with CRA 5 without extra peer dependencies.
- **Tests in `backend/tests/`**, not next to the source, so `src/` holds only runtime code.

## 5. Edge cases and known limitations
- The health test checks that the timestamp round-trips through `Date`, so an invalid timestamp fails the test.
- The frontend mock is removed in `afterEach`, so it can't leak into future test files.
- The offline path ("Backend Offline") has no test yet. It is out of scope for a smoke test.
- `CLAUDE.md` lives in `hcl_tech/`, outside the git repo, by the driver's choice, so teammates who clone the repo won't have it.
- `docs/API_DOCUMENTATION.md` leaves the `message` field out of the health response. This is template text and was left unchanged.
- `npm install` reports audit warnings from `react-scripts` 5 transitive dependencies. They are dev-only and unchanged by this task.

## 6. Run and test
```bash
cd backend && npm test      # expect: Tests: 2 passed, 2 total
cd frontend && npm test     # expect: Tests: 1 passed, 1 total
cd backend && npm start     # expect: Server listening on http://localhost:5000
curl http://localhost:5000/api/health   # expect: 200 {"status":"UP",...}
cd frontend && npm start    # open http://localhost:3000, API Health Status card shows "UP" with a green dot
```

## 7. Change scenarios
1. **If someone changes the `/api/health` body or its status code in `backend/src/app.js`,** the backend health test fails. Update the route and the test together, and keep `status: 'UP'`, because `App.jsx` compares against that string.
2. **If someone adds `app.listen` back into `app.js`,** tests open port 5000. They then fail with `EADDRINUSE` whenever the dev server is running, and Jest won't exit. Keep `listen` only in `server.js`.
3. **If someone renames the API Health card text or changes `getApiHealth` in `frontend/src/services/api.js`,** `App.test.jsx` fails on `findByText('UP')` or on the `/health` URL check. Fix the component or the test there.

## 8. Driver
Pranjal

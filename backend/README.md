# Backend Service — HCL Hackathon

## 📁 Directory Structure
- `src/controllers/`: Route handlers and logic
- `src/routes/`: Express endpoint definitions
- `src/models/`: Database schema definitions
- `src/middleware/`: Authentication and error handling
- `src/services/`: Core business logic
- `src/utils/`: Helper functions

## 🛠️ Setup & Execution

### 1. Install Dependencies
```bash
npm install
```

### 2. Configure Environment
Copy `.env.example` to `.env` and configure your local settings:
```bash
cp .env.example .env
```

### 3. Run Locally
- **Development Mode** (with hot reload):
  ```bash
  npm run dev
  ```
- **Production Mode**:
  ```bash
  npm start
  ```

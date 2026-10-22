# API Documentation

## Base URL
- Local Development: `http://localhost:5000/api`
- Production: `https://your-api-domain.com/api`

---

## Authentication
All protected routes require a Bearer token in the `Authorization` header:
```text
Authorization: Bearer <your_jwt_token>
```

---

## Standard Response Format

### Success Response (`200 OK`, `201 Created`)
```json
{
  "success": true,
  "data": { ... },
  "message": "Operation successful"
}
```

### Error Response (`400 Bad Request`, `401 Unauthorized`, `500 Server Error`)
```json
{
  "success": false,
  "error": "Error description message"
}
```

---

## Core Endpoints

### 1. Health Check
- **Endpoint**: `GET /health`
- **Description**: Verifies API status and server availability.
- **Response**:
```json
{
  "status": "UP",
  "timestamp": "2026-10-22T10:30:00.000Z"
}
```

### 2. Authentication
- **Endpoint**: `POST /auth/register`
- **Payload**:
```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "password": "SecurePassword123!"
}
```

- **Endpoint**: `POST /auth/login`
- **Payload**:
```json
{
  "email": "jane@example.com",
  "password": "SecurePassword123!"
}
```

### 3. Resource Endpoints (Customize for your Hackathon Project)
- `GET /items`: Fetch all items / records
- `POST /items`: Create a new item
- `GET /items/:id`: Fetch item details
- `PUT /items/:id`: Update item
- `DELETE /items/:id`: Delete item

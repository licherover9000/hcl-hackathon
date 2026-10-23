FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY common.py serve.py train.py ./
EXPOSE 8001
CMD ["uvicorn", "serve:app", "--host", "0.0.0.0", "--port", "8001"]

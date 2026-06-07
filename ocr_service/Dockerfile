FROM python:3.10-slim

# System libs required by OpenCV / PaddleOCR
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxrender1 \
    libxext6 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ocr_service.py .

EXPOSE 8000
CMD ["uvicorn", "ocr_service:app", "--host", "0.0.0.0", "--port", "8000"]

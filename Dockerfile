FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY server/requirements.txt /app/server/requirements.txt
RUN python -m pip install --no-cache-dir -r /app/server/requirements.txt && groupadd -g 10001 maxvpn && useradd -u 10001 -g maxvpn -M maxvpn
COPY server/ /app/server/
COPY site/remote.html site/style.css /app/site/
USER maxvpn
EXPOSE 8000
CMD ["uvicorn", "server.public:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]

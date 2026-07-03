"""
把 docs/ 目录下的真实文件灌入 PaiSmart 知识库，供 recall 评测使用。
一次性脚本，不是 k6 负载测试的一部分。

用法：
    python load-tests/scripts/seed_docs.py
"""
import hashlib
import json
import math
import os
import sys
import time
import urllib.request
import urllib.error

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8081")
USERNAME = os.environ.get("K6_USERNAME", "admin")
PASSWORD = os.environ.get("K6_PASSWORD", "admin123")
CHUNK_SIZE = 5 * 1024 * 1024

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS_DIR = os.path.join(REPO_ROOT, "docs")

# 只挑文本类可解析文档，跳过二进制图片/表格类不适合做检索评测的文件
CANDIDATE_FILES = [
    "project-report.md",
    "PaiSmart_Project_Report.docx",
    "project-tasks/PaiSmart_Biweekly_Reports.md",
    "databases/ddl.sql",
    "nginx.conf",
    "generate_report.py",
    "henry-hou-cv-se.pdf",
]


def post_json(path, payload, headers=None):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def login():
    status, body = post_json("/api/v1/users/login", {"username": USERNAME, "password": PASSWORD})
    if status != 200 or not body.get("data", {}).get("token"):
        print(f"LOGIN FAILED: status={status} body={body}", file=sys.stderr)
        sys.exit(1)
    return body["data"]["token"]


def multipart_post(path, fields, file_field_name, file_name, file_bytes, headers=None):
    boundary = f"----k6seed{int(time.time()*1000)}"
    lines = []
    for key, value in fields.items():
        lines.append(f"--{boundary}".encode())
        lines.append(f'Content-Disposition: form-data; name="{key}"'.encode())
        lines.append(b"")
        lines.append(str(value).encode())
    lines.append(f"--{boundary}".encode())
    lines.append(
        f'Content-Disposition: form-data; name="{file_field_name}"; filename="{file_name}"'.encode()
    )
    lines.append(b"Content-Type: application/octet-stream")
    lines.append(b"")
    lines.append(file_bytes)
    lines.append(f"--{boundary}--".encode())
    lines.append(b"")
    body = b"\r\n".join(lines)

    req = urllib.request.Request(f"{BASE_URL}{path}", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def upload_file(token, filepath):
    with open(filepath, "rb") as f:
        content = f.read()

    file_md5 = hashlib.md5(content).hexdigest()
    file_name = os.path.basename(filepath)
    total_size = len(content)
    total_chunks = max(1, math.ceil(total_size / CHUNK_SIZE))
    auth = {"Authorization": f"Bearer {token}"}

    print(f"  uploading {file_name} ({total_size} bytes, {total_chunks} chunk(s), md5={file_md5})")

    for i in range(total_chunks):
        chunk = content[i * CHUNK_SIZE : min((i + 1) * CHUNK_SIZE, total_size)]
        fields = {
            "fileMd5": file_md5,
            "chunkIndex": str(i),
            "totalSize": str(total_size),
            "fileName": file_name,
            "totalChunks": str(total_chunks),
            "isPublic": "true",
        }
        status, body = multipart_post(
            "/api/v1/upload/chunk", fields, "file", file_name, chunk, headers=auth
        )
        if status != 200:
            print(f"    CHUNK {i} FAILED: status={status} body={body}", file=sys.stderr)
            return None

    status, body = post_json(
        "/api/v1/upload/merge",
        {"fileMd5": file_md5, "fileName": file_name},
        headers={**auth, "Content-Type": "application/json"},
    )
    if status != 200:
        print(f"    MERGE FAILED: status={status} body={body}", file=sys.stderr)
        return None

    print(f"    merged OK -> object_url={body['data']['object_url']}")
    return {"fileName": file_name, "fileMd5": file_md5}


def main():
    print(f"Logging in as {USERNAME}...")
    token = login()
    print("Login OK.\n")

    uploaded = []
    for rel_path in CANDIDATE_FILES:
        filepath = os.path.join(DOCS_DIR, rel_path)
        if not os.path.exists(filepath):
            print(f"SKIP (not found): {filepath}")
            continue
        result = upload_file(token, filepath)
        if result:
            uploaded.append(result)

    out_path = os.path.join(REPO_ROOT, "load-tests", "data", "seeded-files.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(uploaded, f, ensure_ascii=False, indent=2)

    print(f"\nSeeded {len(uploaded)}/{len(CANDIDATE_FILES)} files.")
    print(f"Manifest written to {out_path}")
    print("Now wait for Kafka consumer to parse+vectorize, then check ES doc count before running recall eval.")


if __name__ == "__main__":
    main()

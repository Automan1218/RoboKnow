"""
把 docs/ 里的真实文本内容复制放大成 N 份「内容不同」的文档，灌进知识库，
用于把 QPS 容量测试（scenario4）跑在接近真实体量的索引上，而不是 7 个文档的玩具库。

做法：取 project-report.md + biweekly report 的真实段落，按段落级别打乱重组
+ 加唯一编号前缀，生成 N 个内容不同（MD5 不同、能通过后端去重校验）但风格一致的
「变体文档」。这不是伪造检索质量数据——只用于跑 scenario4 的 QPS/p95，不用于 recall 评测。

用法：
    python load-tests/scripts/amplify_docs.py --count 200
"""
import argparse
import hashlib
import math
import os
import random
import sys
import urllib.request
import urllib.error
import json
import time

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS_DIR = os.path.join(REPO_ROOT, "docs")
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8081")
USERNAME = os.environ.get("K6_USERNAME", "admin")
PASSWORD = os.environ.get("K6_PASSWORD", "admin123")
CHUNK_SIZE = 5 * 1024 * 1024

SOURCE_FILES = [
    "project-report.md",
    "project-tasks/PaiSmart_Biweekly_Reports.md",
]


def login():
    data = json.dumps({"username": USERNAME, "password": PASSWORD}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/v1/users/login", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read())
    return body["data"]["token"]


def multipart_post(path, fields, file_field_name, file_name, file_bytes, headers):
    boundary = f"----k6amp{int(time.time()*1000)}"
    lines = []
    for key, value in fields.items():
        lines.append(f"--{boundary}".encode())
        lines.append(f'Content-Disposition: form-data; name="{key}"'.encode())
        lines.append(b"")
        lines.append(str(value).encode())
    lines.append(f"--{boundary}".encode())
    lines.append(f'Content-Disposition: form-data; name="{file_field_name}"; filename="{file_name}"'.encode())
    lines.append(b"Content-Type: text/plain")
    lines.append(b"")
    lines.append(file_bytes)
    lines.append(f"--{boundary}--".encode())
    lines.append(b"")
    body = b"\r\n".join(lines)
    req = urllib.request.Request(f"{BASE_URL}{path}", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def post_json(path, payload, headers):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in headers.items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def load_paragraphs():
    paras = []
    for rel in SOURCE_FILES:
        path = os.path.join(DOCS_DIR, rel)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        for p in text.split("\n\n"):
            p = p.strip()
            if len(p) > 40:
                paras.append(p)
    return paras


def make_variant(paras, seed, min_paras=15, max_paras=40):
    rng = random.Random(seed)
    n = rng.randint(min_paras, max_paras)
    chosen = rng.sample(paras, min(n, len(paras)))
    rng.shuffle(chosen)
    header = f"# Synthetic Variant Document #{seed}\n\nGenerated for load-test corpus amplification, seed={seed}.\n\n"
    return header + "\n\n".join(chosen)


def upload(token, content, file_name):
    content_bytes = content.encode("utf-8")
    file_md5 = hashlib.md5(content_bytes).hexdigest()
    total_size = len(content_bytes)
    total_chunks = max(1, math.ceil(total_size / CHUNK_SIZE))
    auth = {"Authorization": f"Bearer {token}"}

    for i in range(total_chunks):
        chunk = content_bytes[i * CHUNK_SIZE : min((i + 1) * CHUNK_SIZE, total_size)]
        fields = {
            "fileMd5": file_md5,
            "chunkIndex": str(i),
            "totalSize": str(total_size),
            "fileName": file_name,
            "totalChunks": str(total_chunks),
            "isPublic": "true",
        }
        status, body = multipart_post("/api/v1/upload/chunk", fields, "file", file_name, chunk, auth)
        if status != 200:
            return None, f"chunk failed: {body}"

    status, body = post_json(
        "/api/v1/upload/merge", {"fileMd5": file_md5, "fileName": file_name},
        {**auth, "Content-Type": "application/json"},
    )
    if status != 200:
        return None, f"merge failed: {body}"
    return file_md5, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=100, help="number of synthetic documents to generate")
    args = ap.parse_args()

    print(f"Logging in as {USERNAME}...")
    token = login()
    print("Login OK.\n")

    paras = load_paragraphs()
    print(f"Loaded {len(paras)} source paragraphs from {SOURCE_FILES}")

    ok, failed = 0, 0
    for i in range(args.count):
        content = make_variant(paras, seed=i)
        file_name = f"synthetic-variant-{i:04d}.md"
        md5, err = upload(token, content, file_name)
        if err:
            failed += 1
            print(f"  [{i}] FAILED: {err}", file=sys.stderr)
        else:
            ok += 1
            if (i + 1) % 20 == 0:
                print(f"  [{i+1}/{args.count}] uploaded ({ok} ok, {failed} failed)")

    print(f"\nDone: {ok} uploaded, {failed} failed out of {args.count}.")
    print("Now wait for Kafka to finish parsing+vectorizing before running scenario4 for realistic QPS numbers.")


if __name__ == "__main__":
    main()

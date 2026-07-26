"""
为 recall 评测灌入「干扰文档」，把知识库从 7 个文档扩到上百个，让 top-10 检索有区分度。

与 amplify_docs.py 的关键区别：
    amplify_docs.py 直接重组真实段落，会把【含答案的段落】复制进干扰文档 → 污染 recall，
    所以它自己也注明"只用于 QPS，不用于 recall"。
    本脚本先从 golden-set.json 收集所有 answerSpans，再从源段落里【剔除任何包含 answerSpan
    的段落】，只用剩下的「安全段落」拼装干扰文档，并在上传前二次校验生成内容不含任何 span。
    这样干扰文档话题相近（是更真实的 hard negative），但保证不含答案，不会污染 Recall。

用法：
    python load-tests/scripts/seed_distractors.py --count 200

跑完等 Kafka 解析+向量化完成，再跑 scenario7-recall-eval.js。
"""
import argparse
import hashlib
import json
import math
import os
import random
import sys
import time
import urllib.error
import urllib.request

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS_DIR = os.path.join(REPO_ROOT, "docs")
GOLDEN_SET = os.path.join(REPO_ROOT, "load-tests", "data", "golden-set.json")
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8081")
USERNAME = os.environ.get("K6_USERNAME", "admin")
PASSWORD = os.environ.get("K6_PASSWORD", "admin123")
CHUNK_SIZE = 5 * 1024 * 1024

# 与 amplify_docs.py 相同的源，用真实项目文本做话题相近的干扰
SOURCE_FILES = [
    "project-report.md",
    "project-tasks/RoboKnow_Biweekly_Reports.md",
]


def load_answer_spans():
    with open(GOLDEN_SET, "r", encoding="utf-8") as f:
        golden = json.load(f)
    spans = []
    for item in golden:
        spans.extend(item.get("answerSpans", []))
    # 去重，去掉过短的 span（<3 字符）以免误伤过多段落
    return sorted({s for s in spans if len(s) >= 3}, key=len, reverse=True)


def contains_span(text, spans):
    return any(span in text for span in spans)


def login():
    data = json.dumps({"username": USERNAME, "password": PASSWORD}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/v1/users/login", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read())
    return body["data"]["token"]


def multipart_post(path, fields, file_field_name, file_name, file_bytes, headers):
    boundary = f"----k6dist{int(time.time()*1000)}"
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


def load_safe_paragraphs(spans):
    """加载源段落，剔除任何包含 answerSpan 的段落。"""
    paras, dropped = [], 0
    for rel in SOURCE_FILES:
        path = os.path.join(DOCS_DIR, rel)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        for p in text.split("\n\n"):
            p = p.strip()
            if len(p) <= 40:
                continue
            if contains_span(p, spans):
                dropped += 1
                continue
            paras.append(p)
    return paras, dropped


def make_variant(paras, spans, seed, min_paras=15, max_paras=40):
    rng = random.Random(seed)
    n = rng.randint(min_paras, max_paras)
    chosen = rng.sample(paras, min(n, len(paras)))
    rng.shuffle(chosen)
    header = (
        f"# Synthetic Distractor Document #{seed}\n\n"
        f"Generated for recall-eval corpus enlargement (answer-free), seed={seed}.\n\n"
    )
    content = header + "\n\n".join(chosen)
    # 二次保险：拼完再校验一次绝不含任何 span
    if contains_span(content, spans):
        raise RuntimeError(f"distractor seed={seed} 意外包含 answerSpan，请检查过滤逻辑")
    return content


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
        "/api/v1/upload/merge",
        {"fileMd5": file_md5, "fileName": file_name},
        {**auth, "Content-Type": "application/json"},
    )
    if status != 200:
        return None, f"merge failed: {body}"
    return file_md5, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=200, help="干扰文档数量")
    args = ap.parse_args()

    spans = load_answer_spans()
    print(f"从 golden-set 收集到 {len(spans)} 个 answerSpan，用于过滤答案段落")

    paras, dropped = load_safe_paragraphs(spans)
    print(f"加载安全段落 {len(paras)} 段（剔除含答案段落 {dropped} 段）")
    if len(paras) < 15:
        print("安全段落不足，无法生成有意义的干扰文档", file=sys.stderr)
        sys.exit(1)

    print(f"登录 {USERNAME}...")
    token = login()
    print("登录成功。\n")

    ok, failed = 0, 0
    for i in range(args.count):
        content = make_variant(paras, spans, seed=i)
        file_name = f"distractor-{i:04d}.md"
        md5, err = upload(token, content, file_name)
        if err:
            failed += 1
            print(f"  [{i}] FAILED: {err}", file=sys.stderr)
        else:
            ok += 1
            if (i + 1) % 20 == 0:
                print(f"  [{i+1}/{args.count}] uploaded ({ok} ok, {failed} failed)")

    print(f"\n完成：{ok} 篇上传，{failed} 篇失败，共 {args.count}。")
    print("等 Kafka 解析+向量化完成后，运行 scenario7-recall-eval.js 得到 chunk 级 Recall@10。")


if __name__ == "__main__":
    main()

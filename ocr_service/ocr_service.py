import logging

import fitz  # pymupdf
import paddle.inference as paddle_infer
from fastapi import FastAPI, File, HTTPException, UploadFile

# i7-1360P (13th-gen hybrid) has no AVX-512; SelfAttentionFusePass in Paddle 2.6
# contains AVX-512 instructions that crash with SIGILL on this CPU family.
# Intercept create_predictor and strip the offending pass before the engine builds.
_orig_create_predictor = paddle_infer.create_predictor


def _safe_create_predictor(config):
    try:
        config.delete_pass("self_attention_fuse_pass")
    except Exception:
        pass
    return _orig_create_predictor(config)


paddle_infer.create_predictor = _safe_create_predictor

from paddleocr import PaddleOCR  # noqa: E402  must import after patch

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI()

# Initialize once at startup; model files downloaded on first run
ocr = PaddleOCR(use_angle_cls=True, lang="ch", use_gpu=False, show_log=False)

PAGE_TEXT_THRESHOLD = 50  # chars below which a page is treated as image-only


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/ocr")
async def ocr_document(file: UploadFile = File(...)):
    try:
        pdf_bytes = await file.read()
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        page_texts = []

        for page_num, page in enumerate(doc):
            embedded = page.get_text("text").strip()
            if len(embedded) >= PAGE_TEXT_THRESHOLD:
                # Text-based page — use embedded text directly
                page_texts.append(embedded)
                logger.info("page %d: %d chars via embedded text", page_num, len(embedded))
            else:
                # Image-based page — run OCR
                mat = fitz.Matrix(2.0, 2.0)  # 2x upscale improves accuracy
                pix = page.get_pixmap(matrix=mat)
                img_bytes = pix.tobytes("png")
                result = ocr.ocr(img_bytes, cls=True)
                if result and result[0]:
                    lines = [line[1][0] for line in result[0] if line and len(line) > 1]
                    page_text = "\n".join(lines)
                    page_texts.append(page_text)
                    logger.info("page %d: %d chars via OCR", page_num, len(page_text))
                else:
                    logger.warning("page %d: OCR returned no result", page_num)

        doc.close()
        combined = "\n\n".join(page_texts)
        return {"text": combined, "pages": len(page_texts)}

    except Exception as e:
        logger.error("OCR failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))

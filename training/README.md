# Nadaka training kit (runs on the iQOO phone in Termux)

**Train on the phone CPU, run on the phone NPU.** The Hexagon NPU only runs
finished models (inference), so training runs on the CPU in Termux. The
exported model is then converted and run on the NPU by the app.

## What it trains

An image classifier (MobileNetV3-Small, fine-tuned). The default use is **Indian
currency notes** for PAY mode, as a second check next to OCR. It works for any
folder-per-class dataset, e.g. medicine strips.

## 1. Setup (once, needs internet)

```
cd ~/storage/downloads/nadaka-training     # after termux-setup-storage
bash setup_termux.sh
python train.py --selftest                 # 1-minute check, no real data needed
```

## 2. Collect data (phone camera)

One folder per class inside `data/`:

```
data/10/  data/20/  data/50/  data/100/  data/200/  data/500/  data/other/
```

- 40+ photos per class; front AND back; flat, folded, held in hand.
- Vary light (bright, dim, shop tube light), angle, distance, background.
- `other/`: hands, wallet, receipts, medicine strips: things that are not notes.
- Move photos from `DCIM/Camera` into the folders with the Files app.

## 3. Train (don't start until the data is in)

```
python train.py --data data --epochs 8
```

Outputs in `out/`: `best.pt`, `labels.txt`, `model.onnx`. Expect several minutes
per epoch on the phone CPU; keep it on charge and in a cool spot.

## 4. Put it on the NPU (laptop, Green Light)

Move `out/` to the laptop with Office Kit file transfer, then either:

- **Qualcomm AI Hub**: compile `model.onnx` for the Snapdragon 8 Elite Gen 5
  (SM8850) to TFLite/QNN (needs a free AI Hub account), or
- **onnx2tf**: `pip install onnx2tf tensorflow` then `onnx2tf -i model.onnx -oiqt`
  for an int8 TFLite.

Copy the `.tflite` + `labels.txt` into `app/src/main/assets/`; the app's QNN
delegate runs it on the NPU.

## Files

| File | What |
|---|---|
| `setup_termux.sh` | Installs Python, PyTorch, torchvision in Termux |
| `train.py` | Fine-tunes, keeps the best epoch, exports ONNX + labels; `--selftest` smoke test |
| `data/` | Your photos, one folder per class |

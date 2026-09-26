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

---

# Ego-motion training (approach detection)

Teaches the app to tell **"something is coming at me"** from **"it only looks
bigger because I'm walking"**. Method and research: `docs/ego-motion.md`.

## Record (phone, Nadaka app)

1. **Long-press the screen**: "Recording". The top bar shows `REC`.
2. Walk normally. Mix: walking past static things (chairs, tables), standing
   while a teammate walks at you, walking while a teammate walks at you, turning.
3. **Press volume-up** the moment someone starts coming toward you ("Approaching",
   bar shows `REC+`), press again when they stop or pass ("Clear").
4. **Long-press again**: "Recording saved". File: `Download/nadaka-logs/ego_*.csv`.
5. Record **at least 3 walks** of 2-3 minutes each.

## Train (Termux or laptop, numpy only)

```
python train_ego.py --selftest                       # check setup, no data needed
python train_ego.py ~/storage/downloads/nadaka-logs/ego_*.csv
```

It holds out the last walk, prints precision/recall for the **physics rule vs the
trained model**, prints each feature's learned weight, and writes `ego_model.json`.
Copy it into `app/src/main/assets/` and rebuild: the app uses it automatically.

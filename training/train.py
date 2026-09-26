"""Fine-tune MobileNetV3-Small on a folder-per-class image dataset.

Runs on CPU: in Termux on the iQOO phone, or on a laptop. The Hexagon NPU is
inference-only, so we train here, export ONNX, convert it (see README), and the
app runs the result on the NPU.

    python train.py --data data            # train on data/<class>/*.jpg
    python train.py --selftest             # 1-epoch smoke test on random images, no download
"""
import argparse
import random
import shutil
import tempfile
import time
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Subset
from torchvision import datasets, models, transforms

MEAN, STD = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225]


def make_selftest_data(root: Path):
    from PIL import Image
    for cls in ("a", "b"):
        (root / cls).mkdir(parents=True)
        for i in range(12):
            Image.new("RGB", (160, 160), tuple(random.randrange(256) for _ in range(3))).save(root / cls / f"{i}.jpg")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", default="data")
    p.add_argument("--out", default="out")
    p.add_argument("--epochs", type=int, default=8)
    p.add_argument("--freeze-epochs", type=int, default=3, help="train only the head first")
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--img", type=int, default=224)
    p.add_argument("--threads", type=int, default=6)
    p.add_argument("--selftest", action="store_true")
    a = p.parse_args()

    torch.set_num_threads(a.threads)
    torch.manual_seed(0)
    tmp = None
    if a.selftest:
        tmp = Path(tempfile.mkdtemp())
        make_selftest_data(tmp)
        a.data, a.epochs, a.freeze_epochs, a.img = str(tmp), 1, 1, 96

    train_tf = transforms.Compose([
        transforms.RandomResizedCrop(a.img, scale=(0.6, 1.0)),
        transforms.RandomRotation(15),
        transforms.ColorJitter(0.4, 0.4, 0.3),  # venue lighting, dim shops
        transforms.ToTensor(), transforms.Normalize(MEAN, STD),
    ])
    val_tf = transforms.Compose([
        transforms.Resize(int(a.img * 1.14)), transforms.CenterCrop(a.img),
        transforms.ToTensor(), transforms.Normalize(MEAN, STD),
    ])
    full = datasets.ImageFolder(a.data)
    classes = full.classes
    idx = list(range(len(full)))
    random.Random(0).shuffle(idx)
    n_val = max(1, len(idx) * 15 // 100)
    train_ds = Subset(datasets.ImageFolder(a.data, train_tf), idx[n_val:])
    val_ds = Subset(datasets.ImageFolder(a.data, val_tf), idx[:n_val])
    train_dl = DataLoader(train_ds, a.batch, shuffle=True)
    val_dl = DataLoader(val_ds, a.batch)
    print(f"{len(classes)} classes {classes}: {len(train_ds)} train / {len(val_ds)} val")

    weights = None if a.selftest else models.MobileNet_V3_Small_Weights.DEFAULT  # ~10 MB download, first run only
    model = models.mobilenet_v3_small(weights=weights)
    model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(classes))
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3)
    loss_fn = nn.CrossEntropyLoss()

    out = Path(a.out)
    out.mkdir(exist_ok=True)
    best = -1.0
    for epoch in range(a.epochs):
        frozen = epoch < a.freeze_epochs
        for prm in model.features.parameters():
            prm.requires_grad = not frozen
        if epoch == a.freeze_epochs:
            for g in opt.param_groups:
                g["lr"] = 1e-4  # gentle once the backbone unfreezes

        model.train()
        t0 = time.time()
        for x, y in train_dl:
            opt.zero_grad()
            loss = loss_fn(model(x), y)
            loss.backward()
            opt.step()

        model.eval()
        correct = total = 0
        with torch.no_grad():
            for x, y in val_dl:
                correct += (model(x).argmax(1) == y).sum().item()
                total += len(y)
        acc = correct / total
        print(f"epoch {epoch + 1}/{a.epochs} {'head' if frozen else 'full'} loss {loss.item():.3f} val_acc {acc:.2%} {time.time() - t0:.0f}s")
        if acc > best:
            best = acc
            torch.save(model.state_dict(), out / "best.pt")

    model.load_state_dict(torch.load(out / "best.pt"))
    model.eval()
    (out / "labels.txt").write_text("\n".join(classes) + "\n")
    try:
        torch.onnx.export(model, torch.zeros(1, 3, a.img, a.img), out / "model.onnx", opset_version=17,
                          input_names=["image"], output_names=["logits"], dynamo=False)
        print(f"exported {out / 'model.onnx'}")
    except Exception as e:  # ONNX export needs the onnx package on some torch builds
        print(f"ONNX export failed ({e}); best.pt is saved, export on the laptop instead")
    print(f"best val_acc {best:.2%} -> {out}/best.pt, labels.txt")

    if tmp:
        shutil.rmtree(tmp)
        assert (out / "best.pt").exists() and (out / "labels.txt").read_text().split() == ["a", "b"]
        print("selftest OK")


if __name__ == "__main__":
    main()

#!/data/data/com.termux/files/usr/bin/bash
# One-time setup of the training kit inside Termux on the iQOO phone.
set -e
termux-setup-storage || true   # tap Allow: lets Termux read ~/storage/downloads
pkg update -y
pkg install -y python python-numpy python-pillow
# Termux ships PyTorch as packages; pip wheels usually don't exist for Termux.
pkg install -y python-torch python-torchvision || pip install torch torchvision
pip install onnx || echo "onnx not installed: training works, ONNX export will be done on the laptop"
python -c "import torch, torchvision; print('torch', torch.__version__, 'vision', torchvision.__version__, 'threads', torch.get_num_threads())"
echo "Setup done. Next: python train.py --selftest"

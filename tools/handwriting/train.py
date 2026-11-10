"""Huấn luyện mô hình viết tay: python train.py --steps 40000 --name v1

Tệp này cố ý không import torch ở cấp module: tiến trình con của feeder (Windows dùng spawn)
nạp lại tệp chính, và mỗi lần nạp DLL CUDA tốn hàng GB bộ nhớ ảo.
"""

if __name__ == "__main__":
    from trainer import main

    main()

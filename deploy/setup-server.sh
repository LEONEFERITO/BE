#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Lightsail 서버 첫 설정 — Ubuntu 24.04 에서 한 번만 돌린다 (ubuntu 사용자로, sudo 권한 필요).
#
#   curl -fsSL https://raw.githubusercontent.com/LEONEFERITO/BE/main/deploy/setup-server.sh -o setup-server.sh
#   bash setup-server.sh
#   (저장소가 비공개라 위 주소가 안 열리면, 파일 내용을 서버에 붙여 넣어 만든다)
#
# 하는 일
#   1. 시간대 Asia/Seoul
#   2. 스왑 2GB — 4GB 서버에서 API · DB · 프론트 빌드가 겹칠 때 메모리가 모자라 죽는 것을 막는다
#   3. Docker Engine + compose 플러그인 (Docker 공식 저장소)
#   4. 보안 업데이트 자동 설치(unattended-upgrades)
#   5. /opt/leoneferito 폴더 (BE · FE 저장소를 여기 받는다)
#
# 방화벽은 Lightsail 콘솔(네트워킹 → IPv4 방화벽)에서 22 · 80 · 443 만 연다. 서버 안의 ufw 는 켜지 않는다 —
# 둘이 어긋나면 SSH 가 막혀 콘솔로만 들어갈 수 있게 된다.
# ─────────────────────────────────────────────────────────────
set -euo pipefail

echo "▶ 시간대"
sudo timedatectl set-timezone Asia/Seoul

echo "▶ 스왑 2GB"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  # 메모리가 남아 있을 때는 스왑을 거의 쓰지 않게
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf >/dev/null
  sudo sysctl -p /etc/sysctl.d/99-swappiness.conf >/dev/null
fi

echo "▶ Docker"
if ! command -v docker >/dev/null; then
  sudo apt-get update -y
  sudo apt-get install -y ca-certificates curl gnupg git
  sudo install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  sudo usermod -aG docker "$USER"
fi

echo "▶ 보안 업데이트 자동 설치"
sudo apt-get install -y unattended-upgrades
sudo dpkg-reconfigure -f noninteractive unattended-upgrades

echo "▶ 작업 폴더"
sudo mkdir -p /opt/leoneferito/backups
sudo chown -R "$USER":"$USER" /opt/leoneferito

echo
echo "✔ 끝. 한 번 로그아웃했다가 다시 접속해야 docker 명령을 sudo 없이 쓸 수 있다."
echo "  다음: BE README '처음 올리기' — /opt/leoneferito 에 BE · FE 저장소를 받는다."

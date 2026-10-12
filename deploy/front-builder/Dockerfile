# 손님 화면(FE) 빌더 이미지 — server.mjs 하나를 띄운다. FE 소스는 이미지에 넣지 않고 볼륨으로 붙인다.
#
# glibc(bookworm) 를 쓴다. alpine(musl) 은 Next 의 SWC 바이너리가 따로 필요해서,
# Windows 에서 만든 package-lock 으로 설치할 때 어긋날 수 있다.
FROM node:22-bookworm-slim

# NODE_ENV 를 여기서 production 으로 두지 않는다 — 그러면 npm ci 가 devDependencies(타입스크립트 · tailwind)를
# 빼고 설치해서 next build 가 실패한다. next build 가 스스로 production 으로 돈다.
ENV NEXT_TELEMETRY_DISABLED=1 \
    TZ=Asia/Seoul

WORKDIR /builder
COPY server.mjs ./server.mjs

# node 사용자로 돈다. 작업 · 사이트 볼륨은 처음 붙을 때 이 사용자 소유로 만들어진다.
RUN mkdir -p /work /site && chown -R node:node /work /site
USER node

EXPOSE 9000
CMD ["node", "/builder/server.mjs"]

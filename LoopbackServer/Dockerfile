# Railway picks this up instead of Railpack, so the build doesn't depend on GitHub release downloads.
FROM oven/bun:1.3-slim
WORKDIR /app
ENV NODE_ENV=production
# No runtime dependencies, so no install step: just the sources.
COPY package.json ./
COPY src ./src
CMD ["bun", "src/index.ts"]

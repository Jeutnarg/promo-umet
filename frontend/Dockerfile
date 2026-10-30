FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-config.sh /docker-entrypoint.d/40-sandbox-config.sh
RUN chmod +x /docker-entrypoint.d/40-sandbox-config.sh
EXPOSE 80

FROM node:24.18.0-alpine3.24 AS dependencies

WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund

FROM dependencies AS development

COPY frontend/ ./

EXPOSE 5173
CMD ["npm", "run", "dev"]

FROM development AS build

RUN npm run build

FROM nginx:1.30.4-alpine3.24 AS runtime

COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080 8443


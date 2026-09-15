# Despliegue en AWS, paso a paso

De una instancia limpia de Ubuntu a la aplicación andando con HTTPS y WSS.

> ## ⚠️ NADA DE ESTO SE EJECUTÓ NUNCA
>
> La máquina de desarrollo no tiene Docker instalado (desvío **D-01**), así que
> ni el `docker-compose.prod.yml`, ni la configuración de Nginx, ni estos pasos
> se corrieron una sola vez. Están escritos para que el despliegue sea
> **mecánico en vez de una investigación en vivo**, no para que salga perfecto
> a la primera.
>
> **Lo que sí está verificado**, y de lo que todo esto depende, está al final
> del documento en "Sobre qué se apoya esto".
>
> Esperá tener que corregir algo. La sección "Cuando algo falla" tiene los
> tres errores más probables con su síntoma exacto.

---

## 0. Antes de tocar la consola de AWS

Hacen falta tres cosas, y **dos de ellas tardan**, así que conviene arrancarlas
primero:

| Qué | Cuánto tarda | Quién |
| :--- | :--- | :--- |
| Un **dominio** apuntando a la IP de la instancia | de minutos a horas (DNS) | Daniel |
| La **clave de Gemini** (opcional) | lo que tarde Google | Daniel |
| La instancia EC2 | 5 minutos | cualquiera |

**El dominio no es opcional.** Let's Encrypt no emite certificados para una IP,
así que sin dominio no hay HTTPS, y sin HTTPS el navegador bloquea el
micrófono: **CU16 por voz no funciona sin TLS**. Con una IP pelada se puede
demostrar todo lo demás, pero no el dictado.

---

## 1. La instancia

- **Tipo:** `t3.small` o mayor. Con `t3.micro` (1 GB) el build de Maven dentro
  de Docker se queda sin memoria — compilar y levantar Spring Boot no entra
  cómodo en 1 GB.
- **Imagen:** Ubuntu Server 22.04 o 24.04 LTS.
- **Disco:** 20 GB.
- **Grupo de seguridad:** abrir **22** (SSH), **80** y **443**. Nada más.
  El 5432 y el 8080 **no se abren**: el compose de producción no los publica
  justamente para que no queden expuestos.

Anotar la IP pública y crear el registro DNS **A** del dominio apuntando ahí.
Comprobar que resolvió antes de seguir:

```bash
dig +short DOMINIO.EJEMPLO      # tiene que devolver la IP de la instancia
```

Si todavía no resolvió, esperá. Pedir el certificado antes de que el DNS
propague falla, y Let's Encrypt limita los reintentos.

---

## 2. Docker

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Para no tener que escribir sudo en cada comando. Hay que volver a entrar por
# SSH para que el grupo tome efecto.
sudo usermod -aG docker $USER
```

Salir y volver a entrar, y comprobar:

```bash
docker compose version    # tiene que responder algo, no "command not found"
```

---

## 3. El código

```bash
git clone https://github.com/Daniel-Bri/sistemacolaborativo.git
cd sistemacolaborativo
```

---

## 4. Los secretos

**Se generan en la instancia y no se commitean nunca.**

```bash
cat > deploy/.env <<EOF
POSTGRES_PASSWORD=$(openssl rand -base64 32)
JWT_SECRET=$(openssl rand -base64 48)
GEMINI_API_KEY=
EOF
chmod 600 deploy/.env
```

Si hay clave de Gemini, pegarla en la última línea. **Si no la hay, dejarla
vacía**: CU16 y CU17 arrancan en modo simulado, lo avisan en el log y la
aplicación levanta igual.

> **Por qué se generan acá y no se copian de ningún lado:** el `JWT_SECRET` que
> vivía como valor por defecto en `application.yml` está en el historial del
> repositorio. La aplicación lo rechaza explícitamente, así que no sirve de
> atajo — pero conviene entender por qué: con esa clave, cualquiera que lea el
> repositorio se fabrica un token válido para cualquier usuario.

---

## 5. El certificado TLS

Se emite **antes** de levantar Nginx, porque Nginx no arranca si los archivos
del certificado no existen.

```bash
sudo apt-get install -y certbot
sudo certbot certonly --standalone -d DOMINIO.EJEMPLO --agree-tos -m TU@EMAIL --no-eff-email
```

`--standalone` levanta un servidor propio en el puerto 80 durante un momento,
así que **el 80 tiene que estar libre**: si ya hubiera algo escuchando, pararlo
primero.

Queda en `/etc/letsencrypt/live/DOMINIO.EJEMPLO/`, que es de donde lo lee el
contenedor de Nginx (montado de sólo lectura).

**Renovación automática:**

```bash
# Certbot instala su propio timer; hay que comprobar que quedó activo.
systemctl list-timers | grep certbot
```

La renovación necesita el puerto 80 o el desafío por webroot. La configuración
de Nginx **ya deja pasar `/.well-known/acme-challenge/` sin redirigir a
HTTPS**, que es el error que hace que el certificado venza a los 90 días sin
que nadie se entere hasta que el sitio deja de abrir.

---

## 6. El dominio en la configuración de Nginx

```bash
sed -i 's/DOMINIO\.EJEMPLO/tu-dominio-real.com/g' deploy/nginx/modelcollab.conf
grep -c "tu-dominio-real.com" deploy/nginx/modelcollab.conf   # tiene que decir 4
```

---

## 7. El frontend

El build de Vite va montado como estático en Nginx.

```bash
cd web
npm ci
npm run build          # deja el resultado en web/dist
cd ..
cp -r web/dist deploy/web-dist
```

> **Dato que hay que confirmar con quien mantiene `web/`:** el cliente tiene que
> apuntar a `https://DOMINIO/api/v1` y a `wss://DOMINIO/ws`. Si esas direcciones
> están fijas en el código apuntando a `localhost:8080`, el build sirve páginas
> pero no conecta con nada. **No lo verifiqué** — es de otra carpeta.

---

## 8. Levantar

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

La primera vez tarda varios minutos: construye la imagen del backend, que
compila el proyecto con Maven adentro.

```bash
docker compose -f deploy/docker-compose.prod.yml ps        # los tres en healthy
docker compose -f deploy/docker-compose.prod.yml logs -f backend
```

Esperar la línea `Started BackendApplication`. **Si no aparece, no está
andando**, por más que el contenedor figure levantado.

---

## 9. Comprobar que anda de verdad

En este orden, porque cada uno depende del anterior:

```bash
# 1. La aplicación responde y el TLS está bien
curl -sS https://DOMINIO.EJEMPLO/api/v1/health

# 2. El HTTP redirige al HTTPS
curl -sI http://DOMINIO.EJEMPLO | head -1          # 301

# 3. El WebSocket levanta a través de Nginx.
#    Sin las cabeceras de Upgrade esto da 400 o 200 en vez de 101.
curl -sI -o /dev/null -w '%{http_code}\n' \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://DOMINIO.EJEMPLO/ws                        # 101
```

**Y después, la única prueba que vale:** abrir el lienzo en dos navegadores,
editar en uno y ver el cambio en el otro. Dejar una de las dos ventanas
**quieta dos minutos** y comprobar que sigue conectada — es lo que detecta el
problema de tiempo de espera de WebSocket, y es invisible en cualquier prueba
más corta que un minuto.

---

## Cuando algo falla

Los tres más probables, con el síntoma real y no con el mensaje de error:

### "La colaboración se cae sola cada minuto"

Tiempo de espera de WebSocket. Pasa si se editó el bloque `location /ws` o si
se cambió la configuración de latidos del backend. Comprobar las dos puntas:

```bash
# ¿Nginx tiene los tiempos largos?
docker compose -f deploy/docker-compose.prod.yml exec nginx \
  grep -A2 proxy_read_timeout /etc/nginx/conf.d/default.conf

# ¿El backend manda latidos? Tiene que decir heart-beat:10000,10000 y NO 0,0.
docker compose -f deploy/docker-compose.prod.yml logs backend | grep -i heartbeat
```

### "Subo una foto y no pasa nada"

Límite de tamaño, y puede ser de dos capas distintas:

- **413 con cuerpo vacío** → es Nginx. Falta `client_max_body_size 12m`.
- **413 con un mensaje en castellano** → es la aplicación, y está bien: la foto
  pesa más de 8 MB de verdad.

### "El backend no levanta"

```bash
docker compose -f deploy/docker-compose.prod.yml logs backend | tail -40
```

Los dos más comunes:

- `Falta JWT_SECRET` → el `.env` no se cargó. Verificar que se pasó
  `--env-file deploy/.env`.
- Error de Flyway → la base quedó a medio migrar de un intento anterior. Con
  datos que no importan: `docker compose -f deploy/docker-compose.prod.yml down
  -v` borra el volumen y arranca limpio. **Con datos que sí importan, no.**

---

## Volver atrás

```bash
docker compose -f deploy/docker-compose.prod.yml down          # conserva los datos
docker compose -f deploy/docker-compose.prod.yml down -v       # BORRA la base
```

Para volver a una versión anterior: `git checkout <commit>` y levantar de nuevo
con `--build`. El volumen de PostgreSQL sobrevive, así que **si el commit
anterior tiene menos migraciones de Flyway que las que ya se aplicaron, no va a
arrancar**. Volver atrás el código es fácil; volver atrás el esquema no.

---

## Sobre qué se apoya esto

Nada de este documento se ejecutó. Lo que sí está comprobado en desarrollo, y
que es de lo que depende que el despliegue funcione:

| Afirmación | Cómo se comprobó |
| :--- | :--- |
| La aplicación se niega a arrancar sin `JWT_SECRET` | Se intentó arrancarla sin la variable: falla con ese mensaje |
| El broker manda latidos cada 10 s | Medido con un cliente STOMP: 10.0, 10.0, 10.0 s |
| Sin latidos, una sala inactiva no manda **ningún** byte | Se revirtió el arreglo y se midió: `heart-beat: 0,0` y cero frames en 26 s |
| Una foto de 3 MB llega hasta el validador | Verificado por HTTP contra el servidor |
| La suite pasa sin PostgreSQL alcanzable | 346 tests en verde con la base apuntando a un puerto muerto |
| El ZIP generado compila | El arnés lo compila de verdad en cada corrida |

Lo que **no** está comprobado, y es lo que hay que mirar primero si algo sale
mal: todo lo que toca Docker, Nginx, TLS y el DNS.

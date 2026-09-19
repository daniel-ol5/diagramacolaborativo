# Despliegue en AWS, paso a paso

De una instancia limpia de Ubuntu a la aplicación andando con HTTPS y WSS.

> ## ⚠️ NADA DE ESTO SE EJECUTÓ NUNCA
>
> La máquina de desarrollo no tiene Docker instalado (desvío **D-01**), así que
> ni el `docker-compose.prod.yml`, ni la configuración de Nginx, ni estos pasos
> se corrieron una sola vez.
>
> **Un instructivo con ramas de falla que nunca se ejecutó sigue siendo cero
> evidencia.** Lo que sigue está escrito para que el despliegue sea mecánico en
> vez de una investigación en vivo, y para que **se pueda falsificar**: cada
> paso dice qué tenés que ver si salió bien. Si no lo ves, el paso no salió,
> aunque no haya aparecido ningún error.
>
> Lo que sí está verificado —y de lo que todo esto depende— está al final, en
> "Sobre qué se apoya esto".

---

## Cómo leer esto

Cada paso tiene dos cosas además del comando:

- **Qué tenés que ver.** La observación concreta que dice que salió bien. Un
  paso sin observación no se puede verificar, sólo se puede creer.
- **Si no lo ves.** Qué hacer, ahí mismo.

**Regla que ahorra una tarde: si un paso no da lo que dice, parás ahí.** El
error más caro de un despliegue no es que algo falle, es enterarte en el paso 9
de que el 4 salió mal. Los síntomas no se parecen a sus causas y vas a terminar
buscando en el lugar equivocado.

El orden **no es el lógico, es el de riesgo**: primero lo que más
probablemente falle.

---

# ENSAYO LOCAL, ANTES DE TOCAR LA INSTANCIA

**Hacé esto primero.** Lo que más probablemente falle de todo el despliegue es
**la construcción de la imagen, que compila el proyecto con Maven adentro**, y
eso se puede provocar en la máquina de desarrollo: hay más memoria que en una
`t3.small`, el repositorio está a mano, y no depende de que exista ninguna
instancia. Si falla allá, se depura por SSH de madrugada; si falla acá, se
arregla ahora.

**Lo que este ensayo NO prueba**, y conviene tenerlo clarísimo: el certificado,
el DNS, el grupo de seguridad de AWS y el comportamiento de Nginx con TLS.
Esas cuatro cosas siguen sin ejecutarse nunca.

## L.0 Que Docker pueda arrancar un motor Linux

**Este paso existe porque ya nos frenó.** En esta máquina Docker Desktop estaba
instalado y corriendo, y aun así **ningún comando funcionaba**: contestaba
`500 Internal Server Error ... check if the server supports the requested API
version`, que suena a versiones y no lo es.

```powershell
(Get-CimInstance Win32_ComputerSystem).HypervisorPresent
Get-CimInstance Win32_Processor | Select-Object VirtualizationFirmwareEnabled
Get-CimInstance Win32_OptionalFeature -Filter "Name='VirtualMachinePlatform' OR Name='Microsoft-Windows-Subsystem-Linux'" | Select-Object Name, InstallState
```

**Qué tenés que ver:** `HypervisorPresent` en `True`,
`VirtualizationFirmwareEnabled` en `True`, y las dos características con
`InstallState = 1` (habilitadas).

**Si `VirtualizationFirmwareEnabled` es `False`:** la virtualización está
apagada en el BIOS/UEFI. Hay que entrar y habilitarla (en AMD se llama **SVM
Mode**; en Intel, **VT-x** o **Intel Virtualization Technology**).

**Si las características dicen `2` (deshabilitadas)** — que es lo que pasó acá —
hay que habilitarlas en una **PowerShell como Administrador** y **reiniciar**:

```powershell
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
# reiniciar Windows, y despues:
wsl --install --no-distribution
```

`--no-distribution` alcanza: Docker Desktop trae su propia distribución y no
hace falta instalar Ubuntu.

**Recién cuando esto responde, seguí:**

```bash
docker version
```

**Qué tenés que ver:** las dos secciones, Client **y Server**. Si sólo aparece
Client, el motor sigue sin arrancar.

## L.1 Los secretos

```bash
printf 'POSTGRES_PASSWORD=%s
JWT_SECRET=%s
GEMINI_API_KEY=
'   "$(openssl rand -base64 32)" "$(openssl rand -base64 48)" > deploy/.env
cat deploy/.env
```

**Qué tenés que ver:** tres líneas, las dos primeras con valores largos.

## L.2 La imagen del backend — **el paso que importa**

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml   --env-file deploy/.env build backend
```

Tarda **entre 5 y 15 minutos la primera vez** y pasa un rato largo sin imprimir
nada mientras Maven baja las dependencias. **No está colgado.**

**Qué tenés que ver:** la última línea diciendo que la imagen se construyó, sin
ningún `ERROR`.

**Si no lo ves:**

| Lo que dice | Qué pasó | Qué hacer |
| :--- | :--- | :--- |
| `Killed`, o el build corta sin explicación | Se quedó sin memoria | Subirle memoria a Docker Desktop (Settings → Resources), 4 GB o más |
| `./mvnw: not found` o `bad interpreter` | El `mvnw` entró con finales de línea de Windows | `backend/.gitattributes` ya lo fuerza a LF; comprobar con `git ls-files --eol backend/mvnw` |
| Errores de red bajando dependencias | Conexión | Reintentar; el build reusa lo ya bajado |

**Este es el resultado que hay que reportar antes que cualquier otra cosa.**
Todo lo demás del despliegue depende de que esta imagen se construya.

## L.3 Revisar el compose combinado

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml   --env-file deploy/.env config | grep -E "published|letsencrypt|sin-tls"
```

**Qué tenés que ver:** el puerto publicado (`8090` por defecto) y
`modelcollab-sin-tls.conf`. **No tiene que aparecer `letsencrypt` ni el 443**:
si aparecen, el override no se está aplicando y el `up` va a fallar.

## L.4 Levantar

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml   --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml ps
```

**Qué tenés que ver:** los tres servicios `Up`, con `postgres` y `backend` en
`(healthy)`.

**Si dice `port is already allocated`:** algo ocupa el 8090. Se cambia sin
editar nada:

```bash
MC_PUERTO_WEB=9000 docker compose -f deploy/docker-compose.prod.yml   -f deploy/docker-compose.local.yml --env-file deploy/.env up -d
```

## L.5 Las comprobaciones que sí se pueden hacer acá

```bash
curl -sS http://localhost:8090/api/v1/health

curl -sI -o /dev/null -w '%{http_code}
'   -H "Connection: Upgrade" -H "Upgrade: websocket"   -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="   http://localhost:8090/ws
```

**Qué tenés que ver:** la respuesta de salud, y `101` en el WebSocket.

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml   logs backend | grep "Started "
```

**Qué tenés que ver:** `Started BackendApplication`. Un contenedor `Up` sólo
dice que el proceso no terminó.

**Sobre la página en `/`:** si no copiaste el build de Vite a `deploy/web-dist`,
Nginx devuelve 404 ahí. **No es un fallo del ensayo**: la API y el WebSocket
andan igual, y lo que se está probando es el backend. Para probar la web
también, hacé el paso 6 de más abajo antes de levantar.

## L.6 Bajar

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.local.yml down -v
```

El `-v` acá sí conviene: son datos de ensayo y dejar la base sucia entre
intentos confunde el próximo.

---

# PASO 0 — Los tres que más probablemente fallen

**Unos 25 minutos, y sirven para enterarte temprano.** Los tres son
independientes del resto: si alguno no sale, mejor saberlo antes de invertir
una hora en lo demás.

## 0.a El DNS, que es el que más tarda y no depende de vos

Crear el registro **A** del dominio apuntando a la IP pública de la instancia,
y **arrancar por acá** porque la propagación puede tardar horas.

```bash
dig +short DOMINIO.EJEMPLO
```

**Qué tenés que ver:** la IP pública de la instancia. Nada más.

**Si no lo ves:** todavía no propagó. Seguí con 0.b y 0.c, que no lo necesitan,
y volvé. **No pidas el certificado hasta que esto resuelva**: cada intento
fallido cuenta contra el límite de Let's Encrypt.

## 0.b Docker, y la trampa del grupo

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
sudo usermod -aG docker $USER
```

**Ahora salí de la sesión SSH y volvé a entrar.** El cambio de grupo no aplica
a la sesión abierta, y este es el tropiezo número uno de este paso.

```bash
docker compose version
docker run --rm hello-world
```

**Qué tenés que ver:** una versión (`Docker Compose version v2.x`) y el mensaje
`Hello from Docker!`.

**Si no lo ves:**

| Lo que dice | Qué pasó |
| :--- | :--- |
| `docker: command not found` | La instalación falló. Releé la salida del `apt-get install`. |
| `permission denied ... docker.sock` | No volviste a entrar por SSH. Salí y entrá. |
| `Cannot connect to the Docker daemon` | `sudo systemctl start docker` y reintentá. |

## 0.c La memoria, que es el sospechoso número uno del build

La imagen del backend **compila el proyecto con Maven adentro**. En una
instancia chica eso se queda sin memoria, y el error que da (`Killed`, o el
build cortado sin explicación) no dice "te faltó RAM".

```bash
free -h
```

**Qué tenés que ver:** 2 GB o más de memoria total.

**Hagas lo que hagas, agregá swap antes de construir.** Cuesta un minuto y
elimina la causa más probable de que el paso 8 muera:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

**Qué tenés que ver:** la fila `Swap:` con 2 GB.

## 0.d El puerto 80, que es lo que el certificado necesita

`certbot --standalone` levanta un servidor propio en el 80 y Let's Encrypt
tiene que poder entrar **desde internet**.

```bash
# En la instancia:
sudo apt-get install -y certbot
sudo python3 -m http.server 80 &
```

Desde **otra red** —el celular con datos móviles sirve, el wifi de la misma
casa no siempre— abrir `http://IP-PUBLICA`.

**Qué tenés que ver:** un listado de archivos en el navegador del celular.

**Si no lo ves:** el grupo de seguridad de la instancia no deja entrar al
puerto 80. Arreglalo ahora; si lo dejás para el paso 5, vas a quemar intentos
del certificado buscando el problema en el lugar equivocado.

```bash
sudo kill %1     # bajar el servidor de prueba
```

---

# 1. La instancia

- **Tipo:** `t3.small` o mayor. Con `t3.micro` (1 GB) el build de Maven dentro
  de Docker se queda sin memoria aun con swap.
- **Imagen:** Ubuntu Server 22.04 o 24.04 LTS.
- **Disco:** 20 GB. El build deja imágenes intermedias pesadas.
- **Grupo de seguridad:** abrir **22**, **80** y **443**. Nada más. El 5432 y el
  8080 **no se abren**: el compose de producción no los publica justamente para
  que no queden expuestos.

**Qué tenés que ver:** `df -h /` con al menos 15 GB libres, y el paso 0.d en
verde.

---

# 2. El código

```bash
git clone https://github.com/Daniel-Bri/sistemacolaborativo.git
cd sistemacolaborativo
git log --oneline -1
```

**Qué tenés que ver:** el último commit de la rama que vas a desplegar.

**Si clonaste la rama equivocada:** `git checkout daniel` (o la que
corresponda) antes de seguir. Desplegar `main` cuando el trabajo está en otra
rama es un error que se descubre recién cuando falta una funcionalidad entera.

---

# 3. Los secretos

**Se generan en la instancia y no se commitean nunca.**

```bash
cat > deploy/.env <<EOF
POSTGRES_PASSWORD=$(openssl rand -base64 32)
JWT_SECRET=$(openssl rand -base64 48)
GEMINI_API_KEY=
EOF
chmod 600 deploy/.env
cat deploy/.env
```

**Qué tenés que ver:** tres líneas, las dos primeras con valores largos y
distintos entre sí.

**Si `JWT_SECRET=` quedó vacío:** `openssl` no está instalado.
`sudo apt-get install -y openssl` y rehacé el archivo. Un secreto vacío hace
que el backend **se niegue a arrancar** —a propósito— y el síntoma aparece
recién en el paso 8.

Si hay clave de Gemini, pegarla en la última línea. **Si no la hay, dejarla
vacía**: CU16 y CU17 arrancan en modo simulado, lo avisan en el log, **lo
declaran en sus respuestas** (`simulated: true`, que la interfaz muestra) y la
aplicación levanta igual.

> **Por qué se generan acá y no se copian de ningún lado:** el `JWT_SECRET` que
> vivía como valor por defecto en `application.yml` está en el historial del
> repositorio. La aplicación lo rechaza explícitamente, así que no sirve de
> atajo — pero conviene entender por qué: con esa clave, cualquiera que lea el
> repositorio se fabrica un token válido para cualquier usuario.

---

# 4. El dominio en la configuración de Nginx

```bash
sed -i 's/DOMINIO\.EJEMPLO/tu-dominio-real.com/g' deploy/nginx/modelcollab.conf
grep -c "tu-dominio-real.com" deploy/nginx/modelcollab.conf
```

**Qué tenés que ver:** `4`.

**Si dice 0:** el `sed` no encontró nada, probablemente porque ya lo corriste.
**Si dice otro número:** alguien editó el archivo; revisalo a mano antes de
seguir, porque un `server_name` o una ruta de certificado mal puesta hace que
Nginx no levante y el error habla de archivos, no del dominio.

---

# 5. El certificado TLS

**El paso con más formas de fallar.** Antes de nada, la prueba en seco:

```bash
sudo certbot certonly --standalone --dry-run -d DOMINIO.EJEMPLO \
  --agree-tos -m TU@EMAIL --no-eff-email
```

**Qué tenés que ver:** `The dry run was successful.`

> **Esto es lo más importante de todo el paso.** El ensayo **no cuenta contra
> el límite de Let's Encrypt**, que son 5 fallos por hora: sin él, cinco
> intentos con el DNS a medio propagar te dejan **una hora sin poder pedir el
> certificado**, justo la hora que no tenés. Con el ensayo podés equivocarte
> todas las veces que quieras.

Recién cuando el ensayo pasa, el pedido de verdad:

```bash
sudo certbot certonly --standalone -d DOMINIO.EJEMPLO \
  --agree-tos -m TU@EMAIL --no-eff-email
sudo ls -l /etc/letsencrypt/live/DOMINIO.EJEMPLO/
```

**Qué tenés que ver:** `fullchain.pem` y `privkey.pem`.

**Si el ensayo falla:**

| Lo que dice | Qué pasó | Qué hacer |
| :--- | :--- | :--- |
| `DNS problem: NXDOMAIN` | El dominio no resuelve todavía | Volver a 0.a y esperar |
| `Timeout during connect` | El 80 no es alcanzable desde afuera | Volver a 0.d: es el grupo de seguridad |
| `Address already in use` | Algo está escuchando en el 80 | `sudo ss -lptn 'sport = :80'` y pararlo |
| `too many failed authorizations` | Ya quemaste los 5 intentos | Esperar una hora, o **ir a la rama sin TLS de más abajo** |

**Renovación automática:**

```bash
systemctl list-timers | grep certbot
```

**Qué tenés que ver:** un temporizador `certbot.timer` con su próxima ejecución.

**Si no aparece:** el certificado vence a los 90 días y el sitio deja de abrir
sin aviso. La configuración de Nginx **ya deja pasar
`/.well-known/acme-challenge/` sin redirigir a HTTPS**, que es el otro motivo
clásico de que la renovación falle en silencio.

---

# 6. El frontend

```bash
cd web
npm ci
npm run build
cd ..
cp -r web/dist deploy/web-dist
ls deploy/web-dist/index.html
```

**Qué tenés que ver:** el `index.html` listado, y un `deploy/web-dist/assets/`
con archivos `.js` y `.css`.

**Si `npm ci` falla por memoria:** el swap del paso 0.c también sirve acá.

> **Dato que hay que confirmar con quien mantiene `web/`:** el cliente tiene que
> apuntar a `https://DOMINIO/api/v1` y a `wss://DOMINIO/ws`. Si esas direcciones
> están fijas en el código apuntando a `localhost:8080`, el build sirve páginas
> pero no conecta con nada, y el síntoma es una pantalla que carga y se queda
> vacía. **No lo verifiqué** — es de otra carpeta.

Comprobación barata que vale la pena:

```bash
grep -ro "localhost:8080" deploy/web-dist/ | head
```

**Qué tenés que ver:** nada. Si aparece algo, el build quedó apuntando a la
máquina de desarrollo.

---

# 7. Revisar el compose antes de levantarlo

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env config > /dev/null
```

**Qué tenés que ver:** ninguna salida. Silencio es éxito.

**Si dice `falta JWT_SECRET` o `falta POSTGRES_PASSWORD`:** el `.env` no se
está leyendo. Comprobá que estás parado en la raíz del repositorio y que el
archivo existe con esos nombres exactos. **Este mensaje acá vale una hora**: es
el mismo error que, sin esta comprobación, aparecería recién en el paso 8 como
"el backend no levanta".

---

# 8. Levantar

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

**La primera vez tarda entre 5 y 15 minutos** y pasa un rato largo sin
imprimir nada mientras Maven baja las dependencias. **Eso es normal, no está
colgado.**

```bash
docker compose -f deploy/docker-compose.prod.yml ps
```

**Qué tenés que ver:** los tres servicios `Up`, y `postgres` y `backend` con
`(healthy)`.

```bash
docker compose -f deploy/docker-compose.prod.yml logs backend | grep "Started "
```

**Qué tenés que ver:** una línea `Started BackendApplication in N seconds`.

**Si el contenedor figura levantado pero esa línea no está, no está andando.**
Un contenedor `Up` sólo dice que el proceso no terminó.

**Si no lo ves:**

| Lo que dice el log | Qué pasó | Qué hacer |
| :--- | :--- | :--- |
| `Killed` durante el build, o el build corta sin error | Se quedó sin memoria | Volver a 0.c y agregar swap |
| `Falta JWT_SECRET` | El `.env` no se cargó | Paso 7 |
| `FlywayException` / `Migration checksum mismatch` | La base quedó a medio migrar de un intento anterior | Ver "Volver a empezar limpio" |
| `Connection refused` a postgres | El backend arrancó antes que la base | No debería pasar (hay healthcheck); `docker compose restart backend` |
| Nginx: `cannot load certificate` | El paso 5 no terminó | Volver al 5, o ir a la rama sin TLS |

---

# 9. Comprobar que anda de verdad

En este orden, porque cada uno depende del anterior:

```bash
# 1. La aplicación responde y el TLS está bien
curl -sS https://DOMINIO.EJEMPLO/api/v1/health
```
**Qué tenés que ver:** una respuesta JSON, sin ninguna advertencia de
certificado por parte de `curl`.

```bash
# 2. El HTTP redirige al HTTPS
curl -sI http://DOMINIO.EJEMPLO | head -1
```
**Qué tenés que ver:** `HTTP/1.1 301 Moved Permanently`.

```bash
# 3. El WebSocket levanta a través de Nginx
curl -sI -o /dev/null -w '%{http_code}\n' \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://DOMINIO.EJEMPLO/ws
```
**Qué tenés que ver:** `101`. Un `400` o un `200` significa que a `location /ws`
le faltan las cabeceras de Upgrade.

```bash
# 4. Una ruta del frontend que no es un archivo
curl -sI https://DOMINIO.EJEMPLO/invite/prueba | head -1
```
**Qué tenés que ver:** `200`. Si da `404`, falta el `try_files` y **todos los
enlaces de invitación están rotos** — que es la única puerta por la que entra
un colaborador nuevo.

**Y después, las dos únicas pruebas que valen:**

1. Abrir el lienzo en dos navegadores, editar en uno y ver el cambio en el
   otro. Dejar una de las dos ventanas **quieta dos minutos** y comprobar que
   sigue conectada: es lo que detecta el problema de tiempo de espera de
   WebSocket, invisible en cualquier prueba más corta que un minuto.
2. Entrar desde el celular por el enlace de invitación. Es el camino que más
   depende de cosas que no probamos.

---

# SI EL CERTIFICADO NO SALE

**Esta rama existe para que no haya que inventarla a las once de la noche.**

## Lo primero: decidir si conviene insistir

- ¿El ensayo (`--dry-run`) llega a fallar por `NXDOMAIN`? Es el DNS: si el
  registro está bien puesto, esperar es la respuesta y suele resolverse solo.
- ¿Falla por `Timeout during connect`? Es el grupo de seguridad de AWS, se
  arregla en dos minutos desde la consola, y **conviene arreglarlo** — no vale
  la pena resignar HTTPS por esto.
- ¿Ya quemaste los cinco intentos? Hay una hora de espera. Si esa hora la
  tenés, esperá. **Si no la tenés, seguí leyendo.**

## Levantar sin TLS

El archivo `deploy/nginx/modelcollab-sin-tls.conf` está escrito y listo. En
`deploy/docker-compose.prod.yml`, en el servicio `nginx`:

1. Cambiar el montaje de la configuración para que apunte a
   `./nginx/modelcollab-sin-tls.conf`.
2. Quitar el montaje de `/etc/letsencrypt` y el puerto `443`.

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
curl -sS http://IP-PUBLICA/api/v1/health
```

No hace falta reemplazar ningún dominio: esa configuración atiende cualquier
nombre, **así que funciona con la IP pelada de la instancia**, que es
justamente el caso en el que uno termina acá.

**Y el frontend NO hay que recompilarlo.** Lo verifiqué en `web/src/services/
config.ts`: en producción la API sale relativa al origen y el WebSocket elige
el esquema **en tiempo de ejecución**, siguiendo el protocolo con el que el
navegador cargó la página (`wss:` si entró por HTTPS, `ws:` si entró por HTTP).
O sea que el mismo build sirve para las dos ramas y no hay nada que rehacer a
la madrugada.

## Qué se pierde, dicho sin adornos

**El dictado por voz en el navegador deja de funcionar.** `getUserMedia()` sólo
existe en un contexto seguro: sin HTTPS, Chrome y Firefox no dan acceso al
micrófono y no hay manera de convencerlos.

## Qué sigue funcionando, que es casi todo

- El modelado colaborativo entero, con `ws://` en vez de `wss://`.
- **CU16 escribiendo el comando en vez de dictándolo.** El cuadro de texto
  acepta exactamente las mismas órdenes: lo que se pierde es el micrófono, no
  el asistente.
- CU17 entero, incluida la captura desde el celular.
- La generación del backend y de la app móvil, la validación y el XMI.
- **La app móvil graba igual.** No depende del contexto seguro del navegador:
  es una aplicación nativa y le pide el micrófono al sistema operativo. **Si
  hay que demostrar voz sin certificado, se demuestra desde el teléfono.**

## Y si más tarde el certificado sale

No hay que rehacer nada: la configuración sin TLS **deja pasar el desafío de
Let's Encrypt igual**, a propósito. Se emite el certificado, se vuelve a
apuntar el montaje a `modelcollab.conf`, se rehace el build del frontend con
`https`/`wss` y se levanta de nuevo.

---

# Cuando algo falla — índice por síntoma

### "La colaboración se cae sola cada minuto"

Tiempo de espera de WebSocket. Pasa si se editó el bloque `location /ws` o si
se cambió la configuración de latidos del backend. Comprobar las dos puntas:

```bash
docker compose -f deploy/docker-compose.prod.yml exec nginx \
  grep -A2 proxy_read_timeout /etc/nginx/conf.d/default.conf
docker compose -f deploy/docker-compose.prod.yml logs backend | grep -i heartbeat
```
**Qué tenés que ver:** tiempos de 3600s, y `heart-beat:10000,10000` — **no**
`0,0`.

### "Subo una foto y no pasa nada"

Límite de tamaño, y puede ser de tres capas distintas. **El cuerpo de la
respuesta dice cuál:**

| Respuesta | Quién la dio | Qué significa |
| :--- | :--- | :--- |
| 413 con **cuerpo vacío** | Nginx | Falta `client_max_body_size 12m` |
| 413 con `"La imagen es demasiado grande"` | La aplicación | Pasó de 10 MB, el límite de multipart |
| 400 con `"La imagen pesa N MB y el máximo son 8"` | El validador | Está bien: la foto pesa demasiado de verdad |

Las tres están comprobadas en desarrollo con archivos reales de 4,99 / 9,20 y
11,24 MB. La primera fila es la única que sería culpa del despliegue.

### "El backend no levanta"

```bash
docker compose -f deploy/docker-compose.prod.yml logs backend | tail -40
```
La tabla del paso 8 tiene los cinco casos.

### "La página carga pero no pasa nada"

El frontend no encuentra la API. Abrir la consola del navegador: si dice
`localhost:8080`, es el paso 6.

### "El enlace de invitación da 404"

Falta `try_files` en Nginx. Comprobación 4 del paso 9.

---

# Volver a empezar limpio

**Por nivel, del más suave al más destructivo.** Usá el más suave que resuelva.

```bash
# 1. Sólo reiniciar los servicios. No pierde nada.
docker compose -f deploy/docker-compose.prod.yml restart

# 2. Bajar y volver a levantar. CONSERVA la base.
docker compose -f deploy/docker-compose.prod.yml down
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d

# 3. Reconstruir la imagen del backend desde cero (si sospechás del build).
docker compose -f deploy/docker-compose.prod.yml build --no-cache backend

# 4. Borrar TODO, incluida la base. Irreversible.
docker compose -f deploy/docker-compose.prod.yml down -v
```

**El nivel 4 borra el volumen de PostgreSQL**: usuarios, proyectos y diagramas.
Con datos de prueba es exactamente lo que querés cuando Flyway quedó a medias.
Con datos que importan, **no**.

Antes del nivel 4, si hay algo que rescatar:

```bash
docker compose -f deploy/docker-compose.prod.yml exec postgres \
  pg_dump -U postgres modelcollab > respaldo-$(date +%F-%H%M).sql
```

**Volver a una versión anterior del código:** `git checkout <commit>` y levantar
con `--build`. El volumen de PostgreSQL sobrevive, así que **si el commit
anterior tiene menos migraciones de Flyway que las ya aplicadas, no va a
arrancar**. Volver atrás el código es fácil; volver atrás el esquema no.

---

# Sobre qué se apoya esto

Nada de este documento se ejecutó. Lo que sí está comprobado en desarrollo, y
que es de lo que depende que el despliegue funcione:

| Afirmación | Cómo se comprobó |
| :--- | :--- |
| La aplicación se niega a arrancar sin `JWT_SECRET` | Se intentó arrancarla sin la variable: falla con ese mensaje |
| El broker manda latidos cada 10 s | Medido con un cliente STOMP: 10.0, 10.0, 10.0 s |
| Sin latidos, una sala inactiva no manda **ningún** byte | Se revirtió el arreglo y se midió: `heart-beat: 0,0` y cero frames en 26 s |
| Los tres límites de tamaño de foto contestan **con texto** | Verificado por HTTP con archivos reales de 4,99 / 9,20 / 11,24 MB |
| Una propuesta de foto llega al navegador | Suscriptor STOMP real contra el servidor levantado (`evidencias/verificar-captura.mjs`) |
| La suite pasa sin PostgreSQL alcanzable | 402 tests en verde con la base apuntando a un puerto muerto |
| El ZIP generado compila y su mapeo JPA es válido | El arnés lo compila de verdad en cada corrida |
| El backend generado **arranca contra PostgreSQL y acepta un alta** | Cuatro diagramas de referencia, una fila por entidad, base creada y borrada en cada corrida |
| La vista previa del esquema coincide con el ZIP | Comparación contra el Java realmente generado, probada en rojo tres veces |

Lo que **no** está comprobado, y es lo que hay que mirar primero si algo sale
mal: **todo lo que toca Docker, Nginx, TLS y el DNS**. Ninguna línea de esas
cuatro cosas corrió nunca.

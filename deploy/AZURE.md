# Desplegar ModelCollab en Azure — la guía completa, de cero a HTTPS

> **Este documento se sigue de arriba abajo y no hay que abrir otro.** Tiene todos los comandos, en
> el orden en que se escriben, con lo que tenés que ver después de cada uno y la falla más probable
> de cada paso.
>
> [`DESPLIEGUE.md`](DESPLIEGUE.md) sigue siendo la referencia larga: por qué cada cosa es como es,
> el `CAMINO B` del túnel sin servidor y el ensayo local con Docker en Windows. **Para desplegar en
> Azure no lo necesitás.** Cuando algo de acá sale mal y la explicación corta no alcanza, cada
> bloque dice a qué sección de ese documento ir.
>
> Reemplaza a `AZURE_PORTAL.md`, que cubría sólo el portal. La versión para AWS es
> [`AWS_CONSOLA.md`](AWS_CONSOLA.md) — **hacé una sola de las dos.**

---

## Lo que hay que saber antes de empezar, sin adornos

**Nada de esta cadena se ejecutó nunca.** Lo dicen las cabeceras de
[`docker-compose.prod.yml`](docker-compose.prod.yml), del
[`Dockerfile` del backend](../backend/Dockerfile) y de
[`nginx/modelcollab.conf`](nginx/modelcollab.conf): la máquina de desarrollo no tiene Docker. **La
primera corrida real es ésta**, y va a haber que corregir algo. Por eso el orden de los bloques no
es el orden lógico: **es el orden que te hace descubrir temprano lo que puede frenarte.**

**Lo que sí está comprobado**, y de lo que depende que esto ande:

| | |
| :--- | :--- |
| El backend **se niega a arrancar** sin `JWT_SECRET` | Se intentó sin la variable; falla con ese mensaje |
| El frontend en producción **no apunta a `localhost`** | Verificado en [`web/src/services/config.ts`](../web/src/services/config.ts): la API sale relativa al origen y el WebSocket elige `ws:`/`wss:` en tiempo de ejecución, según cómo cargó la página |
| El build del frontend está limpio | `grep -ro "localhost:8080" web/dist/` no devuelve nada, y pesa 497 KB |
| Los latidos del WebSocket salen cada 10 s | Medido: 10.0, 10.0, 10.0 s. Es lo que mantiene viva la conexión a través de Nginx |

**Lo que más probablemente te frene, en orden:** el cupo de máquinas de la cuenta de estudiante
(bloque 1), la memoria durante el build (bloque 4), y el certificado (bloque 7).

---

## El orden, con reloj

Los bloques 0 y 3 **se solapan a propósito** con los que tardan: arrancá el dominio y el build del
frontend temprano y que maduren mientras hacés el resto.

| | Bloque | Dónde | Tarda |
| :--- | :--- | :--- | :--- |
| **0** | El frontend construido | Tu Windows | 3 min |
| **1** | La máquina en el portal | Portal de Azure | 15 min |
| **2** | Entrar por SSH | Git Bash | 5 min |
| **3** | El dominio *(arrancalo apenas tengas la IP del bloque 1)* | duckdns.org | 2 min + propagación |
| **4** | Docker, memoria y el puerto 80 | La máquina | 10 min |
| **5** | El código y los secretos | La máquina | 5 min |
| **6** | El frontend, subido | Git Bash | 2 min |
| **7** | El certificado TLS | La máquina | 5 min |
| **8** | Levantar | La máquina | 5 a 20 min |
| **9** | Comprobar que anda de verdad | Las dos | 10 min |

**Total realista: unas dos horas**, contando que algo va a salir mal una vez.

---

# BLOQUE 0 — El frontend, construido en tu máquina

**Hacelo primero y no en el servidor.** `DESPLIEGUE.md` lo construye en la instancia, y en una
máquina recién creada eso no funciona: **el proyecto usa Vite 8 y TypeScript 6, que necesitan
Node 20 o más nuevo, y Ubuntu 24.04 trae Node 18 en sus repositorios.** Instalar Node en el
servidor es media hora de trampas y compite por la memoria con el build de Maven.

**Tu Windows ya tiene Node 22.19 y el build pesa 497 KB.** Se construye acá y se sube por `scp`.

En **Git Bash**, en la raíz del repositorio:

```bash
cd web
npm ci
npm run build
cd ..
grep -ro "localhost:8080" web/dist/ | head
```

**Qué tenés que ver:** el build termina sin error, y **el `grep` no devuelve nada**.

**Si el `grep` devuelve algo:** el build quedó apuntando a tu máquina de desarrollo y la página va
a cargar en blanco en el servidor. Revisá que no haya un `.env` con `VITE_MC_HTTP_BASE` puesto en
`web/`.

> **Que el build corresponda al código que vas a desplegar.** En el bloque 5 el servidor clona
> desde GitHub, así que si tenés cambios en `web/src/` sin commitear, el frontend y el backend van
> a ser de versiones distintas. `git status --short` no debería mostrar nada bajo `web/src/`.

---

# BLOQUE 1 — La máquina en el portal de Azure

## 1.1 Confirmar la suscripción

Portal → **Suscripciones**. Tiene que aparecer **Azure for Students** con crédito disponible.

**Si no aparece:** la verificación de estudiante no terminó de aplicarse y no vas a poder crear
nada. Es lo primero a resolver.

## 1.2 Elegir la región — el riesgo propio de esta cuenta

Las cuentas de estudiante tienen **un tope de 4 vCPU** y, según la región, **cupo cero para algunas
familias de máquinas**. El error aparece recién al apretar Crear:

```
Operation could not be completed as it results in exceeding approved
standardBSFamily Cores quota
```

**No pierdas tiempo pidiendo aumento de cupo: en las suscripciones de estudiante casi nunca se
otorga. Cambiá de región.**

| Región | Cuándo |
| :--- | :--- |
| **`East US 2`** | **La recomendada**: es donde más veces hay cupo de familia B |
| `East US` | Segunda opción si la primera rechaza |
| `Brazil South` | Menos latencia desde Bolivia, pero la que más seguido no tiene cupo |

Para una demostración la latencia de Estados Unidos no se nota. **Andá con `East US 2`.**

## 1.3 Crear la máquina

Portal → **Máquinas virtuales** → **Crear** → **Máquina virtual de Azure**.

### Pestaña «Básico»

| Campo | Valor | Por qué |
| :--- | :--- | :--- |
| **Grupo de recursos** | **Crear nuevo** → `modelcollab-rg` | La carpeta que vas a borrar al final, de un tirón |
| **Nombre** | `modelcollab` | |
| **Región** | `East US 2` | |
| **Opciones de disponibilidad** | No se requiere redundancia | |
| **Imagen** | **Ubuntu Server 24.04 LTS — x64 Gen2** | |
| **Arquitectura** | **x64** | ⚠️ **No Arm64.** Azure la ofrece al lado y se parece demasiado; las imágenes de Docker están probadas en x64 |
| **Tamaño** | **`Standard_B2s`** — 2 vCPU, 4 GiB | «Ver todos los tamaños». ⚠️ **No `B1s`**: 1 GB no compila |
| **Autenticación** | **Clave pública SSH** | |
| **Nombre de usuario** | `azureuser` | **Anotalo.** No es `ubuntu` como en AWS: es el que escribís vos |
| **Origen de la clave** | **Generar nuevo par de claves** | |
| **Nombre del par** | `modelcollab-key` | |
| **Puertos de entrada** | **Permitir puertos seleccionados** → **SSH (22), HTTP (80), HTTPS (443)** | Los tres, nada más |

> ⚠️ **No abras el 5432 ni el 8080.** El compose de producción **no los publica** justamente para
> que la base de datos y el backend no queden expuestos a internet. Abrirlos acá sería deshacer esa
> protección: se podría entrar sin pasar por TLS, y el `wss://` quedaría decorativo.

### Pestaña «Discos»

**Tipo de disco del SO: SSD estándar (LRS)** — el Premium cuesta más y no aporta nada acá. El
tamaño que viene (30 GiB) ya está arriba de los 20 que hacen falta. **Eliminar con la VM: tildado.**

### Pestaña «Redes»

Dejá lo que viene —crea IP pública y grupo de seguridad de red con los tres puertos— y **tildá
«Eliminar la IP pública y la NIC al eliminar la VM»**, para que no quede nada suelto consumiendo
crédito.

### Pestaña «Administración»

**El apagado automático es opcional.** Si lo activás, ⚠️ **que la hora no caiga antes de la
defensa.** Una máquina que se apaga sola a las 19:00 del 22 es exactamente el problema que no
querés descubrir el 23.

### Crear

**Revisar y crear** → **Crear** → **Descargar clave privada y crear recurso**.

**Se descarga una sola vez.** Si la perdés hay que crear otra máquina. Guardala en
`C:\Users\Daniel\.ssh\modelcollab-key.pem`.

| Si la creación falla diciendo | Qué hacer |
| :--- | :--- |
| `exceeding approved standardBSFamily Cores quota` | Cupo cero en esa región. **Cambiá de región** (1.2). No pidas aumento |
| `The requested size ... is not available in location` | Ese tamaño no existe ahí. Cambiá de región **antes** de bajar de tamaño |
| Algo sobre `Trusted launch` / `Security type` | Volvé a «Básico» → **Tipo de seguridad: Estándar** |

## 1.4 Anotar la IP pública

**Máquina virtual → Información general → Dirección IP pública.** Anotala: la usás en el bloque 2
y en el 3.

**Es estática.** A diferencia de AWS, **no cambia si apagás la máquina**, así que todo el problema
de la IP elástica no existe acá: podés detenerla a la noche y encenderla mañana sin que se caigan
el dominio ni el certificado.

> «Detener» en el portal **libera el cómputo** y deja de consumir crédito. El disco y la IP siguen
> consumiendo, pero son centavos.

**Apenas tengas la IP, andá al BLOQUE 3 y volvé.** El dominio es lo que más tarda.

---

# BLOQUE 2 — Entrar por SSH desde Windows

**Git Bash**, no PowerShell.

**Primero, los permisos del `.pem`.** SSH se niega a usar una clave que cualquiera puede leer, y en
Windows los archivos nacen así. **Este es el punto donde se traba casi todo el mundo:**

```powershell
# En PowerShell, UNA sola vez:
icacls "C:\Users\Daniel\.ssh\modelcollab-key.pem" /inheritance:r
icacls "C:\Users\Daniel\.ssh\modelcollab-key.pem" /grant:r "$($env:USERNAME):(R)"
```

**Después, desde Git Bash:**

```bash
ssh -i ~/.ssh/modelcollab-key.pem azureuser@LA-IP-PUBLICA
```

**El usuario es el que escribiste en 1.3** —`azureuser` si dejaste el que viene—, **no `ubuntu` ni
`root`**. Es la diferencia con AWS que más veces hace perder diez minutos.

**Qué tenés que ver:** el saludo de Ubuntu y un prompt `azureuser@modelcollab:~$`.

| Si ves | Es porque |
| :--- | :--- |
| `Permission denied (publickey)` | El usuario está mal, o el `.pem` no es el de esa máquina |
| `UNPROTECTED PRIVATE KEY FILE` | Faltan los `icacls` de arriba |
| Se queda colgado | El 22 no está en el grupo de seguridad de red, o la máquina sigue arrancando |

**Para agregar o revisar puertos después:** máquina virtual → **Redes** → **Configuración de red** →
**Agregar regla de puerto de entrada**.

---

# BLOQUE 3 — El dominio

**Es lo que más tarda y no depende de nosotros.** Arrancalo apenas tengas la IP y seguí con lo demás
mientras propaga.

En `duckdns.org`, entrando con Google:

1. Elegí un subdominio, por ejemplo `modelcollab`.
2. Pegá la **IP pública** en el campo `current ip`.
3. **update ip**.

Comprobalo **desde tu Windows**:

```bash
nslookup modelcollab.duckdns.org
```

**Qué tenés que ver:** la IP pública de tu máquina. Nada más.

**Si todavía no aparece:** seguí con los bloques 4, 5 y 6, que no lo necesitan, y volvé. **No pidas
el certificado hasta que esto resuelva**: cada intento fallido cuenta contra el límite de Let's
Encrypt.

### La alternativa gratis de Azure, si DuckDNS te da problemas

La IP pública de Azure puede tener nombre propio, y **resuelve al instante, sin esperar
propagación**:

**Grupo de recursos → la IP pública → Configuración → Etiqueta de nombre DNS** → `modelcollab`.

Queda `modelcollab.eastus2.cloudapp.azure.com` y sirve como dominio.

> ⚠️ Let's Encrypt emite para estos nombres —hay gente que lo hace— pero **no lo confirmé yo**. No
> lo descubras gastando intentos: el bloque 7 tiene el **ensayo que no cuenta contra el límite**.
> Ensayá con ese nombre primero; si el ensayo pasa, usalo.

---

# BLOQUE 4 — Docker, la memoria y el puerto 80

**Los tres que más probablemente fallen, y son independientes del resto.** Si alguno no sale, mejor
saberlo ahora que después de invertir una hora.

*Todo este bloque se corre dentro de la máquina, por SSH.*

## 4.0 Fijar el dominio en la sesión

Para no volver a tipearlo en cada comando —y no equivocarte en el que importa:

```bash
echo 'export DOMINIO=modelcollab.duckdns.org' >> ~/.bashrc
echo 'export EMAIL=lolpro777db@gmail.com' >> ~/.bashrc
source ~/.bashrc
echo "$DOMINIO / $EMAIL"
```

**Qué tenés que ver:** tu dominio y tu correo. **Va en `~/.bashrc` a propósito**: en 4.1 hay que
salir y volver a entrar por SSH, y así no se pierde.

## 4.1 Docker, y la trampa del grupo

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

> ⚠️ **Propio de Azure:** las imágenes de Ubuntu vienen con `unattended-upgrades` corriendo al
> arrancar, y los primeros minutos de vida de la máquina el `apt-get` puede fallar con
> `Could not get lock /var/lib/dpkg/lock-frontend`. **No es un error tuyo: es que el sistema se
> está actualizando solo.** Esperá dos minutos y reintentá. Para ver si todavía está:
> `ps aux | grep -i apt`.

**Ahora salí de la sesión SSH y volvé a entrar.** El cambio de grupo no aplica a la sesión abierta,
y este es el tropiezo número uno del bloque.

```bash
exit
```
```bash
ssh -i ~/.ssh/modelcollab-key.pem azureuser@LA-IP-PUBLICA
docker compose version
docker run --rm hello-world
```

**Qué tenés que ver:** una versión (`Docker Compose version v2.x`) y `Hello from Docker!`.

| Lo que dice | Qué pasó |
| :--- | :--- |
| `docker: command not found` | La instalación falló. Releé la salida del `apt-get install` |
| `permission denied ... docker.sock` | No volviste a entrar por SSH. Salí y entrá |
| `Cannot connect to the Docker daemon` | `sudo systemctl start docker` y reintentá |

## 4.2 La memoria — el sospechoso número uno del build

La imagen del backend **compila el proyecto con Maven adentro**. En una máquina chica eso se queda
sin memoria, y el error que da (`Killed`, o el build cortado sin explicación) **no dice «te faltó
RAM»**.

```bash
free -h
```

**Qué tenés que ver:** 3,8 GB o similar en total. Con `B2s` son 4 GiB.

**Hagas lo que hagas, agregá swap antes de construir.** Cuesta un minuto y elimina la causa más
probable de que el bloque 8 muera:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

**Qué tenés que ver:** la fila `Swap:` con 2 GB.

> ⚠️ **Propio de Azure: el swap va en `/swapfile`, sobre el disco del sistema, como está arriba.**
> Azure monta un disco temporal en `/mnt` y es tentador usarlo — **no lo hagas**: ese disco **se
> borra cada vez que se libera la máquina**, y una entrada en `/etc/fstab` apuntando ahí puede
> dejarte una máquina que no arranca. Justo lo que no querés el día antes.

## 4.3 El puerto 80, comprobado desde afuera

`certbot --standalone` levanta un servidor propio en el 80, y Let's Encrypt tiene que poder entrar
**desde internet**.

```bash
sudo apt-get install -y certbot
sudo python3 -m http.server 80 &
```

Desde **otra red** —el celular con datos móviles sirve, el wifi de la misma casa no siempre— abrí
`http://LA-IP-PUBLICA`.

**Qué tenés que ver:** un listado de archivos en el navegador del celular.

**Si no lo ves:** el grupo de seguridad de red no deja entrar al 80. **Arreglalo ahora**; si lo
dejás para el bloque 7, vas a quemar intentos del certificado buscando el problema en el lugar
equivocado. Máquina virtual → **Redes** → **Agregar regla de puerto de entrada** → 80.

> Ubuntu en Azure viene con `ufw` inactivo, así que **el único cortafuegos que importa es el grupo
> de seguridad de red del portal.** Si el 80 está ahí y no entra, no busques en la máquina.

```bash
sudo kill %1     # bajar el servidor de prueba
```

**Comprobá que quedó libre**, porque si no el certificado falla con `Address already in use`:

```bash
sudo ss -lptn 'sport = :80'
```

**Qué tenés que ver:** nada.

---

# BLOQUE 5 — El código y los secretos

## 5.1 Clonar

```bash
git clone https://github.com/Daniel-Bri/sistemacolaborativo.git
cd sistemacolaborativo
git checkout daniel
git log --oneline -1
```

**Qué tenés que ver:** el último commit de la rama `daniel` — el mismo que tenés en tu máquina.

**Comparalo de verdad con el de tu Windows** (`git log --oneline -1`). **Desplegar `main` cuando el
trabajo está en `daniel` es un error que se descubre recién cuando falta una funcionalidad
entera**, en vivo.

## 5.2 Los secretos

**Se generan en la máquina y no se commitean nunca.**

```bash
cat > deploy/.env <<EOF
POSTGRES_PASSWORD=$(openssl rand -base64 32)
JWT_SECRET=$(openssl rand -base64 48)
GEMINI_API_KEY=
EOF
chmod 600 deploy/.env
cat deploy/.env
```

**Qué tenés que ver:** tres líneas, las dos primeras con valores largos y distintos entre sí.

**Si `JWT_SECRET=` quedó vacío:** falta `openssl`. `sudo apt-get install -y openssl` y rehacé el
archivo. **Un secreto vacío hace que el backend se niegue a arrancar —a propósito— y el síntoma
aparece recién en el bloque 8.**

**La clave de Gemini** va en la última línea si la tenés. **Si la dejás vacía, CU16 y CU17 arrancan
en modo simulado**, lo avisan en el log y **lo declaran en sus respuestas** (`simulated: true`, que
la interfaz muestra). La aplicación levanta igual.

> **Por qué se generan acá y no se copian del `.env` de tu máquina:** el `JWT_SECRET` que vivía
> como valor por defecto en `application.yml` **está en el historial del repositorio**. Con esa
> clave, cualquiera que lea el repositorio se fabrica un token válido para cualquier usuario.

---

# BLOQUE 6 — Subir el frontend

**Desde tu Windows, en otra ventana de Git Bash** (no dentro de la máquina), en la raíz del
repositorio:

```bash
scp -i ~/.ssh/modelcollab-key.pem -r web/dist \
  azureuser@LA-IP-PUBLICA:~/sistemacolaborativo/deploy/web-dist
```

Y **de vuelta en la máquina**, comprobalo:

```bash
ls deploy/web-dist/index.html deploy/web-dist/assets/
```

**Qué tenés que ver:** el `index.html`, y en `assets/` un `.js` y un `.css`.

> ⚠️ **Este paso no se puede saltar.** Nginx monta `deploy/web-dist` como sólo-lectura; **si la
> carpeta no existe, Docker la crea vacía y Nginx sirve un 403 sin explicar por qué.** El síntoma
> es «la API anda pero la página no carga», y se busca en el lugar equivocado.

**Si `scp` falla o preferís no depender de él**, el plan B es construir en la máquina — pero
entonces hay que instalar Node 20 o más nuevo a mano, porque el de Ubuntu es viejo para Vite 8:

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
node -v            # tiene que decir v22.x
cd web && npm ci && npm run build && cd ..
cp -r web/dist deploy/web-dist
grep -ro "localhost:8080" deploy/web-dist/ | head
```

**Qué tenés que ver:** `v22.x`, y el `grep` sin resultados. **El plan A es mejor**: menos cosas
instaladas y nada compitiendo por la memoria con Maven.

---

# BLOQUE 7 — El certificado TLS

**El paso con más formas de fallar.** *En la máquina.*

## 7.1 El dominio en la configuración de Nginx

```bash
sed -i "s/DOMINIO\.EJEMPLO/$DOMINIO/g" deploy/nginx/modelcollab.conf
grep -c "$DOMINIO" deploy/nginx/modelcollab.conf
```

**Qué tenés que ver: `5`.**

> `DESPLIEGUE.md` decía `4` en este punto y estaba mal: el dominio aparece en **cuatro
> directivas** (`server_name` dos veces y las dos rutas del certificado) **más una línea de
> comentario** que el `sed` también reemplaza. Son cinco líneas. Si ves 5, está bien.

**Si dice 0:** el `sed` no encontró nada, probablemente porque ya lo corriste.
**Si dice otro número:** alguien editó el archivo. Revisalo a mano antes de seguir: un `server_name`
o una ruta de certificado mal puesta hace que Nginx no levante, **y el error habla de archivos, no
del dominio**.

## 7.2 El ensayo — lo más importante de todo el bloque

```bash
sudo certbot certonly --standalone --dry-run -d "$DOMINIO" \
  --agree-tos -m "$EMAIL" --no-eff-email
```

**Qué tenés que ver:** `The dry run was successful.`

> **El ensayo no cuenta contra el límite de Let's Encrypt**, que son **5 fallos por hora**. Sin él,
> cinco intentos con el DNS a medio propagar te dejan **una hora sin poder pedir el certificado** —
> justo la hora que no tenés. Con el ensayo podés equivocarte todas las veces que quieras.

| Si el ensayo dice | Qué pasó | Qué hacer |
| :--- | :--- | :--- |
| `DNS problem: NXDOMAIN` | El dominio todavía no resuelve | Volvé al bloque 3 y esperá |
| `Timeout during connect` | El 80 no es alcanzable desde afuera | Volvé a 4.3: es el grupo de seguridad de red |
| `Address already in use` | Algo escucha en el 80 | `sudo ss -lptn 'sport = :80'` y pararlo |
| `too many failed authorizations` | Quemaste los 5 intentos | Esperá una hora, **o andá al bloque 10** |

## 7.3 El pedido de verdad

**Recién cuando el ensayo pasa:**

```bash
sudo certbot certonly --standalone -d "$DOMINIO" \
  --agree-tos -m "$EMAIL" --no-eff-email
sudo ls -l /etc/letsencrypt/live/$DOMINIO/
```

**Qué tenés que ver:** `fullchain.pem` y `privkey.pem`.

```bash
systemctl list-timers | grep certbot
```

**Qué tenés que ver:** un `certbot.timer` con su próxima ejecución. Si no aparece, el certificado
vence a los 90 días y el sitio deja de abrir sin aviso. La configuración de Nginx **ya deja pasar
`/.well-known/acme-challenge/` sin redirigir a HTTPS**, que es el otro motivo clásico de que la
renovación falle en silencio.

---

# BLOQUE 8 — Levantar

## 8.1 Revisar el compose antes — vale una hora

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env config > /dev/null
```

**Qué tenés que ver: ninguna salida. El silencio es el éxito.**

**Si dice `falta JWT_SECRET` o `falta POSTGRES_PASSWORD`:** el `.env` no se está leyendo.
Comprobá que estás parado en la raíz del repositorio y que el archivo existe con esos nombres
exactos. **Este mensaje acá vale una hora**: es el mismo error que, sin esta comprobación,
aparecería en 8.2 disfrazado de «el backend no levanta».

## 8.2 Levantar

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

**La primera vez tarda entre 5 y 15 minutos** y pasa un rato largo sin imprimir nada mientras Maven
baja las dependencias. **Eso es normal, no está colgado.**

> ⚠️ **Propio de Azure:** `B2s` es una máquina **de CPU por ráfagas**. Un build de diez minutos
> puede agotar los créditos de ráfaga y **tardar veinte**. Va más lento, no está trabado.

```bash
docker compose -f deploy/docker-compose.prod.yml ps
docker compose -f deploy/docker-compose.prod.yml logs backend | grep "Started "
```

**Qué tenés que ver:** los tres servicios `Up`, `postgres` y `backend` con `(healthy)`, y una línea
`Started BackendApplication in N seconds`.

**Si el contenedor figura levantado pero esa línea no está, no está andando.** Un contenedor `Up`
sólo dice que el proceso no terminó.

| Lo que dice el log | Qué pasó | Qué hacer |
| :--- | :--- | :--- |
| `Killed` durante el build, o corta sin error | Se quedó sin memoria | Volvé a 4.2 y agregá swap |
| `Falta JWT_SECRET` | El `.env` no se cargó | 8.1 |
| `FlywayException` / `Migration checksum mismatch` | La base quedó a medio migrar de un intento anterior | Bloque 11, nivel 4 |
| `Connection refused` a postgres | El backend arrancó antes que la base | No debería pasar (hay healthcheck); `docker compose -f deploy/docker-compose.prod.yml restart backend` |
| Nginx: `cannot load certificate` | El bloque 7 no terminó | Volvé al 7, o andá al bloque 10 |

---

# BLOQUE 9 — Comprobar que anda de verdad

**En este orden, porque cada uno depende del anterior.** *En la máquina.*

```bash
# 1. La aplicación responde y el TLS está bien
curl -sS https://$DOMINIO/api/v1/health
```
**Qué tenés que ver:** una respuesta JSON, **sin ninguna advertencia de certificado de parte de
`curl`**.

```bash
# 2. El HTTP redirige al HTTPS
curl -sI http://$DOMINIO | head -1
```
**Qué tenés que ver:** `HTTP/1.1 301 Moved Permanently`.

```bash
# 3. El WebSocket levanta a través de Nginx
curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 -X GET \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  https://$DOMINIO/ws
```
**Qué tenés que ver: `101`.** Un `400` o un `200` significa que a `location /ws` le faltan las
cabeceras de Upgrade.

> **Dos detalles del comando que cuestan una hora si faltan.** Tiene que ser **`-X GET`**: con
> `-sI` curl manda un **HEAD**, y un endpoint de WebSocket contesta **405 aunque todo funcione**
> —comprobado: el mismo destino da 405 con HEAD y 101 con GET—. Y el **`--max-time`** está porque
> un 101 exitoso deja la conexión abierta y curl espera para siempre.

```bash
# 4. Una ruta del frontend que no es un archivo
curl -sI https://$DOMINIO/invite/prueba | head -1
```
**Qué tenés que ver:** `200`. Si da `404`, falta el `try_files` y **todos los enlaces de invitación
están rotos** — que es la única puerta por la que entra un colaborador nuevo.

## Y después, las dos únicas pruebas que valen

1. **Abrir el lienzo en dos navegadores**, editar en uno y ver el cambio en el otro. Dejar una de
   las dos ventanas **quieta dos minutos** y comprobar que sigue conectada: es lo que detecta el
   problema de tiempo de espera del WebSocket, **invisible en cualquier prueba más corta que un
   minuto**.
2. **Entrar desde el celular por el enlace de invitación.** Es el camino que más depende de cosas
   que no probamos.

---

# BLOQUE 10 — Si el certificado no sale

**Esta rama existe para que no haya que inventarla a las once de la noche.**

## Primero: decidir si conviene insistir

| El ensayo falla por | Qué es | Conviene |
| :--- | :--- | :--- |
| `NXDOMAIN` | El DNS | **Esperar.** Si el registro está bien puesto, se resuelve solo |
| `Timeout during connect` | El grupo de seguridad de red | **Arreglarlo**: son dos minutos en el portal y no vale la pena resignar HTTPS |
| `too many failed authorizations` | Quemaste los 5 intentos | Hay una hora de espera. **Si la tenés, esperá.** Si no, seguí leyendo |

## Levantar sin TLS

`deploy/nginx/modelcollab-sin-tls.conf` **está escrito y listo**. En
`deploy/docker-compose.prod.yml`, en el servicio `nginx`:

1. Apuntar el montaje de la configuración a `./nginx/modelcollab-sin-tls.conf`.
2. Quitar el montaje de `/etc/letsencrypt` y el puerto `443`.

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
curl -sS http://LA-IP-PUBLICA/api/v1/health
```

**No hace falta reemplazar ningún dominio:** esa configuración atiende cualquier nombre, **así que
funciona con la IP pelada**, que es justamente el caso en el que uno termina acá.

**Y el frontend NO hay que recompilarlo.** Verificado en `web/src/services/config.ts`: en
producción la API sale relativa al origen y el WebSocket **elige el esquema en tiempo de
ejecución**, siguiendo el protocolo con el que el navegador cargó la página. **El mismo build sirve
para las dos ramas y no hay nada que rehacer a la madrugada.**

## Qué se pierde, dicho sin adornos

**El dictado por voz en el navegador deja de funcionar.** `getUserMedia()` sólo existe en un
contexto seguro: sin HTTPS, Chrome y Firefox no dan acceso al micrófono y no hay manera de
convencerlos.

## Qué sigue funcionando, que es casi todo

- El modelado colaborativo entero, con `ws://` en vez de `wss://`.
- **CU16 escribiendo el comando en vez de dictándolo.** El cuadro de texto acepta exactamente las
  mismas órdenes: **se pierde el micrófono, no el asistente.**
- CU17 entero, incluida la captura desde el celular.
- La generación del backend y de la app móvil, la validación y el XMI.
- **La app móvil graba igual.** Es nativa y le pide el micrófono al sistema operativo, no al
  navegador. **Si hay que demostrar voz sin certificado, se demuestra desde el teléfono.**

## Y si más tarde el certificado sale

No hay que rehacer nada: la configuración sin TLS **deja pasar el desafío de Let's Encrypt igual**,
a propósito. Se emite el certificado, se vuelve a apuntar el montaje a `modelcollab.conf` y se
levanta de nuevo.

---

# BLOQUE 11 — Cuando algo falla

## Índice por síntoma

| Síntoma | Dónde está | Qué es |
| :--- | :--- | :--- |
| «La colaboración se cae sola cada minuto» | `DESPLIEGUE.md` § *índice por síntoma* | Tiempo de espera del WebSocket. Comprobar `proxy_read_timeout 3600s` en Nginx y `heart-beat:10000,10000` en el log del backend — **no** `0,0` |
| «Subo una foto y no pasa nada» | idem | Límite de tamaño, y **el cuerpo de la respuesta dice de qué capa**: 413 vacío es Nginx, 413 con mensaje es la aplicación, 400 con el peso es el validador |
| «El backend no levanta» | La tabla de 8.2 | Los cinco casos |
| «La página carga pero no pasa nada» | Bloque 0 y 6 | El frontend no encuentra la API. Consola del navegador: si dice `localhost:8080`, el build está mal |
| «El enlace de invitación da 404» | Comprobación 4 del bloque 9 | Falta `try_files` |
| «La página da 403» | Bloque 6 | `deploy/web-dist` está vacía o no existe |

## Volver a empezar limpio

**Del más suave al más destructivo. Usá el más suave que resuelva.**

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

**El nivel 4 borra el volumen de PostgreSQL**: usuarios, proyectos y diagramas. Con datos de prueba
es exactamente lo que querés cuando Flyway quedó a medias. **Con datos que importan, no.**

Antes del nivel 4, si hay algo que rescatar:

```bash
docker compose -f deploy/docker-compose.prod.yml exec postgres \
  pg_dump -U postgres modelcollab > respaldo-$(date +%F-%H%M).sql
```

---

# BLOQUE 12 — Después de la defensa

## Lo que consume, para que no te sorprenda

De tus **100 dólares de crédito**, no de tu tarjeta:

| | Aproximado |
| :--- | :--- |
| `Standard_B2s` encendida | **~1 USD por día** |
| Disco SSD estándar de 30 GB | ~2,50 USD al mes |
| IP pública estática | ~3,50 USD al mes |

**Dos o tres días encendida: unos 3 dólares.** Con el crédito **no hay riesgo de factura**; el
riesgo es dejarla encendida un año y comerte los 100 sin darte cuenta.

## Borrar todo — una sola acción

```
Portal → Grupos de recursos → modelcollab-rg → Eliminar grupo de recursos
```

Pide escribir el nombre para confirmar. **Eso borra la máquina, el disco, la IP y el grupo de
seguridad de una vez** — no hay que ir uno por uno como en AWS, ni queda una IP reservada cobrando
por su cuenta.

> Si querés conservarla unos días: **Detener** la máquina. Deja de consumir el cómputo, **la IP no
> cambia** y el certificado sigue sirviendo cuando la enciendas.

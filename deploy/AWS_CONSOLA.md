# AWS — desde cero hasta poder entrar por SSH

> 🟦 **Si tenés la cuenta de Azure para Estudiantes verificada, no hagas ésta: hacé
> [`AZURE.md`](AZURE.md).** No pide tarjeta, el crédito cubre la demostración, y la IP no se mueve
> al apagar la máquina. Además es **la guía completa de punta a punta** —portal, máquina, código,
> certificado y comprobaciones en un solo documento—, mientras que ésta cubre sólo la consola y
> después te manda a `DESPLIEGUE.md`. **Hacé una sola de las dos.**

> Esta guía cubre **sólo la parte de la consola de AWS**: crear la cuenta, levantar la máquina
> y poder conectarte a ella.
>
> **A partir de ahí seguís con [`DESPLIEGUE.md`](DESPLIEGUE.md)**, que ya está escrito, probado y
> tiene las ramas de falla. No dupliques pasos entre los dos documentos.

---

## Antes de empezar: lo que necesitás tener a mano

| | |
| :--- | :--- |
| **Una tarjeta de crédito o débito** | AWS la exige aunque no gastes. Hace un cargo chico de verificación y lo devuelve |
| **Un teléfono** | Verificación por SMS o llamada |
| **Un correo** | El que quieras, no hace falta que sea institucional |
| **Unos 30 minutos** | Sin contar la propagación del dominio |

**Costo real hasta el 23:** aproximadamente **2 a 3 dólares**, contando la máquina y el disco.

> ⚠️ **Y esto es lo más importante de toda la guía: eliminá la instancia después de la defensa.**
> Si queda encendida sigue cobrando todos los meses. Es el error clásico y sale caro. Poné una
> alarma para el 24.

---

## 1. Crear la cuenta

En `aws.amazon.com` → **Crear una cuenta de AWS**.

- Correo, contraseña, nombre de la cuenta.
- **Tipo de cuenta: Personal.**
- Datos de facturación con la tarjeta.
- Verificación por teléfono.
- **Plan de soporte: Basic (gratuito).** No elijas ninguno de pago.

La activación puede tardar unos minutos. Vas a recibir un correo cuando esté lista.

---

## 2. Elegir la región — **antes de crear nada**

Arriba a la derecha de la consola hay un selector de región. **Elegí una y no la cambies**: los
recursos viven en una región y no se ven desde otra. Si creás la máquina en una y después mirás
en otra, vas a pensar que desapareció.

| Región | Cuándo |
| :--- | :--- |
| **`us-east-1`** *(Norte de Virginia)* | **La recomendada**: la más barata y la que más disponibilidad tiene |
| `sa-east-1` *(São Paulo)* | Menos latencia desde Bolivia, pero **más cara** |

Para una demostración, la latencia de `us-east-1` no se nota. **Andá con ésa.**

---

## 3. Crear el par de claves — **antes de la instancia**

**EC2 → Network & Security → Key Pairs → Create key pair**

- **Nombre:** `modelcollab`
- **Tipo:** RSA
- **Formato: `.pem`**

Se descarga **una sola vez**. Si lo perdés no hay forma de recuperarlo y hay que crear otra
máquina.

**Guardalo en un lugar fijo**, por ejemplo `C:\Users\Daniel\.ssh\modelcollab.pem`.

---

## 4. Crear el grupo de seguridad

**EC2 → Network & Security → Security Groups → Create security group**

- **Nombre:** `modelcollab-sg`
- **Reglas de entrada** — exactamente estas tres:

| Tipo | Puerto | Origen |
| :--- | :---: | :--- |
| SSH | 22 | `0.0.0.0/0` |
| HTTP | 80 | `0.0.0.0/0` |
| HTTPS | 443 | `0.0.0.0/0` |

- **Reglas de salida:** dejá la que viene por defecto (todo permitido).

> ⚠️ **No abras el 5432 ni el 8080.** El compose de producción **no los publica** justamente para
> que la base de datos y el backend no queden expuestos a internet. Abrirlos en el grupo de
> seguridad sería deshacer esa protección.

---

## 5. Lanzar la instancia

**EC2 → Instances → Launch instances**

| Campo | Valor | Por qué |
| :--- | :--- | :--- |
| **Name** | `modelcollab` | |
| **AMI** | **Ubuntu Server 24.04 LTS** (o 22.04) | El instructivo está escrito para Ubuntu |
| **Instance type** | **`t3.small`** | ⚠️ **No `t3.micro`**: con 1 GB, compilar con Maven dentro de Docker se queda sin memoria aunque le agregues intercambio |
| **Key pair** | `modelcollab` | El del paso 3 |
| **Network settings** | **Select existing security group** → `modelcollab-sg` | El del paso 4 |
| **Storage** | **20 GB** gp3 | El build deja imágenes intermedias pesadas |

**Launch instance.**

> **`t3.small` no entra en la capa gratuita** — ésa cubre `t2.micro` y `t3.micro`, que no
> alcanzan. Es la razón del costo.

---

## 6. Fijar la dirección IP — **el paso que casi nadie hace y después duele**

Por defecto, **la IP pública cambia cada vez que apagás y encendés la instancia**. Y el dominio
va a apuntar a esa IP.

> Si apagás la máquina el 22 para ahorrar y la encendés el 23, **la IP cambia, el dominio apunta
> al vacío, y el certificado deja de servir** — justo antes de la defensa.

**Dos salidas:**

**A — No la apagues.** Son tres días. Es lo más simple y lo que recomiendo.

**B — Asignar una IP elástica.** **EC2 → Elastic IPs → Allocate** y asociala a la instancia. La
IP queda fija aunque apagues.

> Ojo con B: una IP elástica **asignada a una instancia encendida es gratis**, pero **si la
> reservás y la dejás sin usar, AWS te cobra**. Si vas por B, acordate de liberarla cuando
> elimines la instancia.

**Anotá la IP pública.** La vas a necesitar en el paso siguiente y en el dominio.

---

## 7. El dominio — **arrancá esto apenas tengas la IP**

**Es lo que más tarda y no depende de nosotros.** Hacelo ahora y que propague mientras seguís
con lo demás.

En `duckdns.org`, entrando con Google:

1. Elegí un subdominio, por ejemplo `modelcollab`.
2. Pegá la **IP pública** de la instancia en el campo `current ip`.
3. **update ip**.

Comprobalo desde tu máquina:

```bash
nslookup modelcollab.duckdns.org
```

**Qué tenés que ver:** la IP pública de tu instancia. Nada más.

**Si todavía no aparece:** no pidas el certificado. Seguí con lo demás y volvé.

---

## 8. Conectarte por SSH desde Windows

**Git Bash**, no PowerShell.

**Primero, los permisos del archivo `.pem`.** SSH se niega a usar una clave que cualquiera puede
leer, y en Windows los archivos nacen así. **Este es el punto donde se traba casi todo el mundo:**

```powershell
# En PowerShell, UNA sola vez:
icacls "C:\Users\Daniel\.ssh\modelcollab.pem" /inheritance:r
icacls "C:\Users\Daniel\.ssh\modelcollab.pem" /grant:r "$($env:USERNAME):(R)"
```

**Después, desde Git Bash:**

```bash
ssh -i ~/.ssh/modelcollab.pem ubuntu@LA-IP-PUBLICA
```

**El usuario es `ubuntu`**, no `root` ni tu nombre. En la imagen de Ubuntu siempre es ése.

La primera vez pregunta si confiás en el servidor: escribí `yes`.

**Qué tenés que ver:** un mensaje de bienvenida de Ubuntu y un prompt `ubuntu@ip-…:~$`.

| Si ves | Es porque |
| :--- | :--- |
| `Permission denied (publickey)` | El usuario está mal, o el `.pem` no es el de esa instancia |
| `UNPROTECTED PRIVATE KEY FILE` | Faltan los `icacls` de arriba |
| Se queda colgado sin responder | El puerto 22 no está abierto en el grupo de seguridad |

---

## 9. A partir de acá, seguí con el otro documento

Ya estás dentro de la máquina. **Abrí [`DESPLIEGUE.md`](DESPLIEGUE.md) y seguí desde el
`CAMINO A — UN SERVIDOR EN LA NUBE`.**

Ese documento cubre, en orden de probabilidad de falla:

| | |
| :--- | :--- |
| **0.a** | El DNS *(ya lo hiciste en el paso 7)* |
| **0.b** | Docker, **y la trampa del grupo**: hay que salir y volver a entrar por SSH o todo da permiso denegado |
| **0.c** | **La memoria**: se agrega intercambio **antes** de construir. El error que da si falta es `Killed`, que no dice "te faltó memoria" |
| **0.d** | El puerto 80 comprobado **desde afuera**, con el celular en datos móviles |
| **1 a 9** | La instancia, el código, los secretos, el dominio, el certificado, el frontend y las comprobaciones |

**Y lo que más te puede salvar la noche, del paso 5:** hay una forma de **ensayar** la emisión del
certificado que **no cuenta contra el límite de cinco intentos por hora** de Let's Encrypt. Usala
siempre antes del intento real.

---

## 10. Después de la defensa — no te olvides

```
EC2 → Instances → seleccionar → Instance state → Terminate
```

**Terminate**, no **Stop**. Detenida sigue cobrando el disco.

Y si asignaste una IP elástica, **liberala también** (`Elastic IPs → Release`).

> **Poné una alarma para el 24.** Es el paso que todo el mundo posterga y el que aparece en el
> resumen de la tarjeta un mes después.

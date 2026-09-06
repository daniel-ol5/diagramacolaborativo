# ModelCollab

Editor colaborativo de diagramas UML/ER en tiempo real que **genera** el backend
Spring Boot y una app móvil con asistente de voz offline a partir del diagrama.

Especificación completa: [`docs/PLAN_ARQUITECTONICO_FASE_0.txt`](docs/PLAN_ARQUITECTONICO_FASE_0.txt)
Plan de ejecución día por día: [`docs/PLAN_EJECUCION.md`](docs/PLAN_EJECUCION.md)
Guía diaria de lo construido: [`docs/dias/`](docs/dias/)

---

## Qué hay en cada carpeta

Son de tres tipos distintos y conviene no mezclarlos.

### Producto — lo que se entrega y corre

| Carpeta | Qué es |
| :--- | :--- |
| **`backend/`** | Servidor Spring Boot 3 + Java 21. Persiste diagramas, arbitra la colaboración en tiempo real (STOMP), valida el modelo y **genera** el código. Incluye las plantillas FreeMarker del backend y de la app móvil generados |
| **`web/`** | Frontend. React + TypeScript + Vite + React Flow: el lienzo donde se dibujan las clases y las relaciones. Arranca el Día 6 |

**No hay carpeta `mobile/` y es a propósito.** ModelCollab no *tiene* una app móvil,
la **genera**: las plantillas viven en `backend/src/main/resources/templates/mobile/`
y el producto es un ZIP distinto por cada diagrama. Una carpeta `mobile/` en la raíz
sugeriría una app escrita a mano, que es exactamente lo contrario de lo que hace el
proyecto.

### Papeles — lo que se lee

| Carpeta | Qué es |
| :--- | :--- |
| **`docs/`** | Los dos planes, la bitácora de desvíos, el veredicto del spike de voz y `dias/`: una guía por día de desarrollo con el detalle técnico y la explicación sin jerga |
| **`evidencias/`** | **Prueba**, no documentación. Capturas y volcados que respaldan que algo ocurrió de verdad: hoy, las fotos del spike de voz en modo avión y el volcado del dispositivo |

La distinción entre `docs/` y `evidencias/` importa: `docs/` es lo que el proyecto
*afirma*, `evidencias/` es lo que sostiene esa afirmación.

### Herramientas — andamio, no se entrega

| Carpeta | Qué es |
| :--- | :--- |
| **`scripts/`** | Utilidades de desarrollo: datos de prueba (`seed-dev.sql`) y clientes de humo que verifican los criterios de aceptación del plan (permisos por rol, exclusión mutua por locks) |
| **`spike-voz/`** | App Flutter desechable escrita el Día 1 para responder **una** pregunta: ¿se puede transcribir voz sin internet en un Android real? Respondida (sí, y el stack es Flutter). **Congelada: no se modifica** |

---

## Un solo repositorio, a propósito

Todo esto vive en **un repositorio**, no en uno por carpeta. Las razones son
específicas de este proyecto, no una preferencia general:

- **El contrato es compartido.** `backend/src/main/java/com/modelcollab/metamodel/`
  define el modelo canónico que consumen a la vez el lienzo web, el generador de
  código, el exportador XMI y la app móvil generada. Con repos separados, un cambio
  ahí obliga a coordinar cuatro *pull requests* y no hay ningún commit que muestre
  el cambio completo.
- **La evidencia tiene que quedar junto al código que la produjo.** Una captura en
  otro repositorio, con otra historia, no se puede fechar contra el commit que la
  generó.
- **La integración continua prueba el conjunto.** El test de humo del Día 12 corre
  el backend y le habla desde `scripts/`. Repos separados no comparten un build.

El despliegue no cambia por esto: el `docker-compose.yml` construye la imagen desde
`backend/`, y el frontend se publica como estáticos desde `web/dist/`. Un repositorio
puede desplegar varios servicios; lo que no se puede es reconstruir la historia de un
proyecto repartida en seis repositorios que nadie sincronizó.

---

## Arrancar

```bash
# Backend (requiere Java 21 y PostgreSQL 16+)
cd backend
./mvnw spring-boot:run          # http://localhost:8080
curl http://localhost:8080/api/v1/health
```

Detalle y trampas conocidas: [`backend/README.md`](backend/README.md).

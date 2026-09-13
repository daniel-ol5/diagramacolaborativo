# Documentación PUDS — ModelCollab

Documentación del sistema siguiendo el **Proceso Unificado de Desarrollo de Software**
(Jacobson, Booch, Rumbaugh).

> **Regla que ordena todo este directorio:** la documentación describe **el sistema que
> existe**, no el que nos gustaría tener. Cada caso de uso, cada clase y cada paquete de
> estas páginas se puede señalar en el código. Lo que todavía no está construido aparece
> marcado como tal, con su estado real.
>
> Un documento PUDS que describe funcionalidad inexistente no es documentación: es una
> propuesta disfrazada, y se desarma con una sola pregunta en la defensa.

---

## Por qué PUDS y no otra cosa

El Proceso Unificado se organiza en **cuatro fases** (Inicio, Elaboración, Construcción,
Transición) y produce **cinco modelos** que se refinan entre sí. Lo que lo hace útil acá
es que es **dirigido por casos de uso**: el caso de uso que se captura en los requisitos
es el mismo que se realiza en el análisis, se diseña, se implementa y se prueba.

Eso da la propiedad que más vale en una defensa: **trazabilidad**. De un requisito se
llega a una clase concreta, y de una clase se vuelve al requisito que la justifica.

```
Requisito (RF-nn)  →  Caso de uso (CU-nn)  →  Realización  →  Clases  →  Código  →  Prueba
```

---

## Estructura del directorio

| Carpeta | Modelo del PUDS | Responde |
| :--- | :--- | :--- |
| `01-captura-requisitos/` | Modelo de **casos de uso** | ¿Quién usa el sistema y para qué? |
| `02-analisis/` | Modelo de **análisis** | ¿Qué conceptos y responsabilidades hay, sin decidir tecnología? |
| `03-diseno/` | Modelo de **diseño** y de **despliegue** | ¿Con qué clases, capas y máquinas se resuelve? |
| `04-implementacion/` | Modelo de **implementación** | ¿Qué componentes y artefactos se construyeron? |
| `05-pruebas/` | Modelo de **pruebas** | ¿Cómo se verifica cada caso de uso? |
| `diagramas/` | — | Fuentes de los diagramas, para regenerarlos |

---

## Estado de avance

| # | Documento | Estado |
| :---: | :--- | :--- |
| 1 | [Actores del sistema](01-captura-requisitos/01-actores.md) | 🟢 |
| 2 | [Casos de uso](01-captura-requisitos/02-casos-de-uso.md) | 🟢 |
| 3 | Especificación detallada de casos de uso | ⬜ siguiente |
| 4 | Requisitos no funcionales | ⬜ |
| 5 | Paquetes de análisis | ⬜ |
| 6 | Clases de análisis | ⬜ |
| 7 | Realizaciones de casos de uso | ⬜ |
| 8 | Diseño: capas y subsistemas | ⬜ |
| 9 | Diseño: modelo de datos | ⬜ |
| 10 | Diseño: despliegue | ⬜ |
| 11 | Implementación: componentes | ⬜ |
| 12 | Pruebas | ⬜ |

---

## Sobre los diagramas

Los diagramas UML de este directorio están escritos en **PlantUML**, en texto, dentro de
`diagramas/`. La razón no es preferencia: un diagrama en texto **se versiona**, se compara
con `git diff` y no se desincroniza del documento que lo explica. Una imagen pegada
envejece en silencio.

Para verlos como imagen:

- **En VS Code:** extensión *PlantUML* (`jebbs.plantuml`) → `Alt+D`
- **En el navegador:** pegar el contenido en <https://www.plantuml.com/plantuml>
- **Exportar a PNG/SVG** para el documento final que se entrega

Se usa PlantUML y no Mermaid porque Mermaid **no tiene notación de casos de uso** y
aproximarla con cajas y flechas produce un diagrama que no es UML. Para un entregable
académico, la notación correcta no es negociable.

---

## Documentos de referencia, fuera de este directorio

| Documento | Qué contiene |
| :--- | :--- |
| [`../PLAN_ARQUITECTONICO_FASE_0.txt`](../PLAN_ARQUITECTONICO_FASE_0.txt) | La especificación congelada: requisitos RF-01 a RF-09, el schema canónico §7, el protocolo STOMP §8, el modelo de datos §13, los endpoints §14 |
| [`../PLAN_EJECUCION.md`](../PLAN_EJECUCION.md) | El plan de 20 días con hitos H1–H5 |
| [`../BITACORA.md`](../BITACORA.md) | Desviaciones respecto al plan y enmiendas autorizadas |
| [`../dias/`](../dias/) | Una guía por día de desarrollo, con las decisiones y su motivo |

**`docs/dias/` y este directorio no compiten: se complementan.** Las guías diarias cuentan
*cómo se llegó* y *qué se descubrió en el camino*; este directorio describe *qué es* el
sistema, en el formalismo que pide la materia.

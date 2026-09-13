# 2.2 Priorizar Casos de Uso

> Modelo de casos de uso — PUDS.
> Define **el orden en que se construyen** los casos de uso y **cómo se reparten en los tres
> ciclos** del parcial. Ordena todo el resto del documento: las secciones 2.3, 2.4, 2.5 y el
> capítulo 3 se organizan por ciclo.

---

## 2.2.1 Criterio de priorización

El Proceso Unificado es **dirigido por casos de uso e impulsado por el riesgo**. La
prioridad no la da la importancia comercial, sino esta pregunta:

> **¿Qué pasa si construyo esto último y resulta que no se puede?**

Un caso de uso que, al fallar, obliga a rehacer todo lo demás va primero, aunque su valor
visible sea bajo. Uno rutinario va después, aunque el usuario lo use todos los días.

| Criterio | Qué mide |
| :--- | :--- |
| **Riesgo arquitectónico** | Si se resuelve mal, ¿cuánto hay que rehacer? |
| **Riesgo técnico externo** | ¿Depende de algo que puede no existir o no funcionar? |
| **Dependencia** | ¿Cuántos otros casos de uso lo necesitan antes? |
| **Valor observable** | ¿El usuario ve algo que sirve al terminar el ciclo? |

---

## 2.2.2 Tabla de priorización

**Riesgo:** 🔴 crítico · 🟠 alto · 🟡 medio · 🟢 bajo

| # | Caso de uso | Riesgo | Por qué | Ciclo |
| :--- | :--- | :---: | :--- | :---: |
| **CU05** | Editar el diagrama en colaboración | 🔴 | Obliga a congelar el modelo canónico, el protocolo, la persistencia y el orden de las operaciones. **Todo lo demás se construye encima**: el lienzo, el generador, el exportador y la app móvil comparten ese mismo modelo | **1** |
| **CU06** | Gestionar bloqueo de elemento | 🔴 | Exclusión mutua distribuida. Resuelta con cola de espera introduce interbloqueo e inanición; la política hay que decidirla antes de tener clientes que dependan de ella | **1** |
| **CU19** | Generar app móvil con asistente de voz | 🔴 | Riesgo **externo**: dependía de si un teléfono transcribe voz sin internet. Si la respuesta era no, cambiaba alcance y cronograma | *spike, ciclo 5* |
| **CU12** | Trabajar sin conexión y sincronizar | 🟠 | Define las reglas de resolución de conflictos. Tocarlas después obliga a revisar cliente, servidor y registro histórico a la vez. Detectar mal la desconexión hace que el usuario **pierda trabajo creyendo que se guardó** | **3** |
| **CU14** | Generar backend Spring Boot | 🟠 | Sostiene la afirmación *"el código generado siempre compila"*, que es la promesa central del proyecto | **3** |
| **CU10** | Gestionar colaboradores del proyecto | 🟠 | Un enlace de invitación mal diseñado **no se puede revocar**: si se filtra, el proyecto queda abierto para siempre | **2** |
| **CU03** | Modelar clases y sus miembros | 🟡 | Ejercita el modelo canónico de punta a punta; los tipos de atributo determinan el código generado | **1** |
| **CU04** | Modelar relaciones | 🟡 | El lado propietario decide el esquema de base de datos generado | **1** |
| **CU07** | Visualizar presencia y cursores | 🟡 | Valida que exista un canal efímero que **no consuma número de orden ni ensucie el historial** | **1** |
| **CU11** | Aceptar invitación | 🟡 | Aceptar no puede **bajar** un rol, o el propietario se degrada probando su propio enlace | **2** |
| **CU13** | Validar diagrama | 🟡 | Precondición de CU14 | **3** |
| **CU15** | Alternar vista UML / ER | 🟡 | Segunda notación sobre el mismo modelo | **3** |
| **CU02** | Iniciar sesión | 🟢 | Rutinario, pero habilita los roles de CU05 y CU06 | **1** |
| **CU01** | Registrarse | 🟢 | Rutinario | **1** |
| **CU08** | Gestionar proyectos | 🟢 | Rutinario | **2** |
| **CU09** | Gestionar diagramas | 🟢 | Rutinario, pero todo lo demás lo necesita | **2** |
| **CU16** | Modelar por comando de voz o texto | 🟡 | Alto valor, ninguna dependencia hacia abajo | 4 |
| **CU17** | Importar diagrama desde foto | 🟡 | Ídem | 4 |
| **CU18** | Intercambiar modelos vía XMI | 🟡 | Ídem | 4 |

---

## 2.2.3 La decisión que hay que saber defender

**El ciclo 1 no empieza por "crear un proyecto", que sería lo natural.** Empieza por la
edición colaborativa, sobre un proyecto y un diagrama cargados directamente en la base de
datos.

Visto desde afuera parece al revés. Es lo correcto, y el motivo es el principio central del
Proceso Unificado:

> Las primeras iteraciones atacan **el riesgo más alto**, no el flujo más obvio.

Crear un proyecto es un formulario: si sale mal, se arregla en una tarde. La edición
colaborativa con exclusión mutua obliga a decidir el modelo canónico, el protocolo, el
orden de las operaciones y la política de bloqueos — y **si eso se decide mal hay que
rehacer el lienzo, el generador y el exportador**, porque los tres se apoyan en el mismo
modelo.

Construir primero los formularios y dejar la colaboración para el final habría significado
descubrir el problema difícil con todo lo demás ya escrito encima.

**La contrapartida se asumió a conciencia:** al terminar el ciclo 1 el sistema todavía no
servía para un usuario real, porque no había forma de crear un proyecto propio. Se resolvió
en el ciclo 2. Un ciclo del Proceso Unificado entrega un incremento **ejecutable y
verificable**, no necesariamente utilizable por un cliente final.

**Y el spike de voz es el mismo principio llevado al extremo.** CU19, el caso de uso más
lejano del cronograma, escondía el riesgo menos controlable: *¿un teléfono transcribe voz
sin internet?* Se respondió **el primer día**, con la aplicación más pequeña posible capaz
de responderlo, en un teléfono real y en modo avión. Si la respuesta hubiera sido no,
cambiaba el alcance del proyecto — y averiguarlo en el último ciclo habría sido fatal.

---

## 2.2.4 Los tres ciclos

### CICLO 1 — Núcleo colaborativo

> **Objetivo:** dos personas abren el mismo diagrama y modelan juntas, sin pisarse.
> **Riesgo que elimina:** el modelo canónico y la exclusión mutua distribuida.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU01 | Registrarse | 🟢 |
| CU02 | Iniciar sesión | 🟢 |
| CU03 | Modelar clases y sus miembros | 🟡 faltan métodos y paquetes en la interfaz |
| CU04 | Modelar relaciones | 🟡 falta modificar una relación existente |
| CU05 | Editar el diagrama en colaboración | 🟢 |
| CU06 | Gestionar bloqueo de elemento | 🟢 |
| CU07 | Visualizar presencia y cursores | 🟢 |

**Resultado verificable:** dos navegadores independientes, con usuarios distintos, sobre el
mismo diagrama. Uno mueve una clase y aparece en el otro sin recargar y **sin parpadeo**;
uno la renombra y el otro ve el candado y no puede editarla; un lector intenta modificar,
recibe el rechazo y **su sesión sobrevive**.

---

### CICLO 2 — Gestión de proyectos y colaboración entre personas

> **Objetivo:** que el sistema deje de depender de datos cargados a mano. Cada usuario crea
> sus proyectos e invita a quien quiera.
> **Riesgo que elimina:** el otorgamiento de roles, que **no estaba en la especificación**.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU08 | Gestionar proyectos | 🟢 |
| CU09 | Gestionar diagramas | 🟢 |
| CU10 | Gestionar colaboradores del proyecto | 🟢 |
| CU11 | Aceptar invitación | 🟢 |

**Resultado verificable:** desde cero, un usuario crea un proyecto, crea un diagrama, genera
un enlace de invitación, y **otro usuario distinto lo abre, entra y edita en vivo** — sin un
solo dato insertado a mano en la base.

> **El hallazgo de este ciclo.** El sistema tenía tres roles definidos, arbitrados y
> probados… y **ninguna forma de otorgarlos**. Apareció justamente al recorrer el catálogo
> completo de casos de uso, que es para lo que sirve esta etapa del proceso.

---

### CICLO 3 — Generación de código y trabajo sin conexión

> **Objetivo:** cerrar la promesa del proyecto —de un diagrama sale código que compila— y
> que la herramienta funcione sin internet.
> **Riesgo que elimina:** la invariante "100% compila" y la resolución de conflictos.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU12 | Trabajar sin conexión y sincronizar | 🟡 servidor listo, falta el navegador |
| CU13 | Validar diagrama | ⬜ |
| CU14 | Generar backend Spring Boot | 🟡 plantillas listas, falta el empaquetado |
| CU15 | Alternar vista UML / Entidad-Relación | ⬜ |

**Resultado verificable:** cortar la red, hacer tres cambios, reconectar y obtener el
informe de reconciliación con lo aplicado y lo descartado, **nombrando los elementos**; y
descargar el proyecto generado de un diagrama y compilarlo.

---

### Fuera del parcial — ciclos 4 y 5

| # | Caso de uso | Ciclo |
| :--- | :--- | :---: |
| CU16 | Modelar por comando de voz o texto | 4 |
| CU17 | Importar diagrama desde foto | 4 |
| CU18 | Intercambiar modelos vía XMI 2.1 | 4 |
| CU19 | Generar app móvil con asistente de voz | 5 |

Se dejan fuera **por prioridad, no por dificultad**. Ninguno es precondición de otro caso de
uso: son hojas del árbol de dependencias. Eso los convierte en lo primero que se recorta si
hay atraso, y en lo último que se construye si no lo hay.

---

## 2.2.5 Resumen

| Ciclo | Casos de uso | 🟢 | 🟡 | ⬜ |
| :---: | :---: | :---: | :---: | :---: |
| **1** | 7 | 5 | 2 | 0 |
| **2** | 4 | 4 | 0 | 0 |
| **3** | 4 | 0 | 2 | 2 |
| Fuera | 4 | 0 | 0 | 4 |
| **Total** | **19** | **9** | **4** | **6** |

**Los ciclos 1 y 2 están cerrados y verificados. El ciclo 3 está en curso.**

# App móvil generada — Barbería Central

Esto **no se escribió a mano**. Son los 12 archivos que ModelCollab produce a partir del
diagrama UML de la barbería, sin que nadie toque una línea entre un dominio y otro.

- **Diagrama de origen:** `backend/src/test/resources/reference-diagrams/barberia.json`
  (5 clases: Cliente, Barbero, Servicio, Cita, Pago)
- **Generado el:** 15 de septiembre de 2026
- **Comprobado con:** `flutter analyze` → *No issues found* (Flutter 3.41.5 / Dart 3.11.3)

Sólo está el **código generado**. Las dependencias y los artefactos de compilación no se
versionan: son megabytes que `flutter pub get` regenera solo.

---

## Cómo se regenera

Desde la aplicación, que es el camino real:

```
POST /api/v1/diagrams/{id}/generate-mobile?apiUrl=https://tu-servidor.com
```

Devuelve el ZIP. Lo puede pedir cualquier miembro del proyecto, lector incluido: generar no
modifica el diagrama.

El parámetro `apiUrl` dice **contra qué servidor habla la app**. Es un parámetro y no una
constante porque una app instalada no se reconfigura: con la dirección clavada en el código,
apuntar a otro servidor obliga a regenerar y reinstalar.

---

## Cómo se corre

```bash
flutter pub get
flutter run                     # o, apuntando a otro servidor:
flutter run --dart-define=MODELCOLLAB_API=https://otro-servidor.com
```

### Lo que hace falta de verdad

**Un teléfono físico con el paquete de voz en español descargado.** No alcanza con tener el
teléfono en español: el modelo de reconocimiento se descarga aparte, desde los ajustes del
sistema (*Ajustes → Sistema → Idiomas → Reconocimiento de voz sin conexión*). Sin ese paquete
la app abre y el micrófono no entiende nada.

**No sirve el emulador** para probar la voz: no tiene micrófono real ni el modelo instalado.
Para el resto —la pantalla, la cola local, el cliente REST— el emulador alcanza, y ahí la
dirección por defecto es `10.0.2.2`, que es como el emulador llama a la máquina anfitriona.

**Para la demostración sin conexión:** modo avión, dictar, y ver que la orden queda guardada y
la app la anuncia por voz. Eso es lo que prueba RF-08.1.

---

## Qué mirar, si alguien quiere entender cómo funciona

| Archivo | Qué hace |
| :--- | :--- |
| `assets/intents.json` | **El catálogo derivado del diagrama.** Es lo único que cambia entre un dominio y otro: 20 intenciones (CREAR/LISTAR/BUSCAR/ELIMINAR por cada una de las 5 clases) con sus frases y sus ranuras de datos |
| `lib/nlu/clasificador.dart` | Decide qué intención dispara una frase dictada. Todo local, sin red |
| `lib/nlu/extractor.dart` | Saca los datos según el tipo: `DATE` resuelve "mañana", `DATETIME` resuelve "a las 3 de la tarde", `DECIMAL` extrae montos |
| `lib/voz/servicio_de_voz.dart` | **El archivo con las tres reglas que salieron del experimento con un teléfono real en modo avión.** Están comentadas ahí, con lo que pasa si se cambian |
| `lib/ui/pantalla_dictado.dart` | Muestra lo que se entendió **antes** de guardar. Una de las acciones posibles es eliminar |
| `lib/datos/cola_local.dart` | SQLite: guarda local primero y sincroniza después |

---

## Una advertencia sobre el archivo de voz

Las tres reglas de `servicio_de_voz.dart` **no son preferencias de estilo**. Cambiar cualquiera
de las tres rompe el funcionamiento sin conexión y **ningún test lo detecta**: el código sigue
compilando y analizando perfecto. Sólo se descubre con un teléfono en modo avión.

La más traicionera es la segunda: sin conexión el reconocedor emite el texto en los resultados
**parciales** y un resultado final **vacío**. Quedarse sólo con el final —que es lo obvio— deja
la app muda. Está explicado en el propio archivo, en segunda persona, para quien vaya a
"simplificarlo".

// Generado por ModelCollab desde el diagrama "${nombreDelDiagrama}".
//
// LA DIRECCION DEL BACKEND VIVE EN UN SOLO LUGAR, Y ES ESTE.
//
// Se eligio al generar el proyecto y se puede cambiar sin regenerar nada:
//
//     flutter run --dart-define=MODELCOLLAB_API=https://otro-servidor.com
//
// Eso importa mas en un telefono que en un navegador. Una pagina web se
// recarga; una app instalada NO SE RECONFIGURA: si la direccion estuviera
// clavada en el codigo, apuntar a otro servidor obligaria a regenerar el
// proyecto y reinstalar la app.
//
// Y ojo con algo que no es obvio: "localhost" dentro de un telefono ES EL
// TELEFONO, no la maquina donde corre el backend. No funciona ni estando las
// dos cosas en la misma red. Las direcciones que sirven de verdad son:
//
//     10.0.2.2        el alias que el emulador de Android le da al host
//     192.168.x.x     la IP de la maquina, para un telefono fisico en la LAN
//     https://...     el servidor desplegado
class Config {
  const Config._();

  /// Contra que backend habla la app.
  static const String urlDelBackend = String.fromEnvironment(
    'MODELCOLLAB_API',
    defaultValue: '${urlDelBackend}',
  );

  /// Prefijo de la API REST del backend generado.
  ///
  /// Es '/api' y NO '/api/v1': el backend que genera ModelCollab expone sus
  /// controladores en @RequestMapping("/api/..."). El '/v1' es del backend de
  /// ModelCollab, que es otra cosa — la app movil no le habla a ese.
  static const String rutaBase = '/api';

  static String url(String recurso) => '$urlDelBackend$rutaBase$recurso';
}

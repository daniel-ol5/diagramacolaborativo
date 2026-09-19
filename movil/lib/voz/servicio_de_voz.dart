// Reutilizado tal cual de las apps que genera CU19 (ver
// evidencias/app-movil-barberia/lib/voz/servicio_de_voz.dart) -- no
// reescrito, porque las tres reglas de abajo salieron de un experimento
// real y perderlas seria repetir el mismo costo.
//
// Reconocimiento de voz (STT) y respuesta hablada (TTS), los dos locales.
//
// ===================================================================
//  LAS TRES REGLAS DE ESTE ARCHIVO SALIERON DE UN EXPERIMENTO CON UN
//  TELEFONO REAL EN MODO AVION. NO SON PREFERENCIAS DE ESTILO.
//  Cambiar cualquiera de las tres rompe el funcionamiento sin conexion,
//  y NINGUN TEST LO DETECTA: todo sigue compilando y analizando bien.
// ===================================================================
import 'package:flutter_tts/flutter_tts.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart';

class ServicioDeVoz {
  final SpeechToText _stt = SpeechToText();
  final FlutterTts _tts = FlutterTts();

  bool _listo = false;
  String? _idiomaElegido;

  /// El ultimo parcial NO VACIO. Ver [_alRecibir]: esto es el resultado.
  String _ultimoParcial = '';

  bool get listo => _listo;
  String? get idioma => _idiomaElegido;

  Future<bool> iniciar() async {
    _listo = await _stt.initialize(
      onError: (dynamic _) {},
      onStatus: (String _) {},
    );
    if (_listo) {
      _idiomaElegido = await _elegirIdioma();
      await _tts.setLanguage(_idiomaElegido ?? 'es');
    }
    return _listo;
  }

  // -------------------------------------------------------- REGLA 3
  //
  // El idioma se elige por MODELO INSTALADO, no por dialecto.
  //
  // El reconocedor lista idiomas que NO tiene descargados: preguntarle si
  // soporta "es-ES" devuelve que si, y despues falla sin conexion porque el
  // modelo que hay instalado es otro. Por eso se recorre lo que declara y se
  // toma el primer español que aparezca, sea el dialecto que sea — es-US,
  // es-ES o es-MX funcionan igual de bien para esto.
  //
  // Por el mismo motivo el catalogo de intenciones dice "es" a secas y no fija
  // dialecto: fijarlo seria elegir por el aparato equivocado.
  Future<String?> _elegirIdioma() async {
    final List<LocaleName> disponibles = await _stt.locales();
    for (final LocaleName local in disponibles) {
      if (local.localeId.toLowerCase().startsWith('es')) {
        return local.localeId;
      }
    }
    return null;
  }

  /// Escucha una orden y devuelve lo que se entendio, o vacio.
  Future<String> escuchar({
    Duration duracion = const Duration(seconds: 8),
  }) async {
    if (!_listo) {
      return '';
    }
    _ultimoParcial = '';

    await _stt.listen(
      onResult: _alRecibir,
      listenOptions: SpeechListenOptions(
        // localeId, pauseFor y listenFor van ACA y no sueltos en listen():
        // sueltos estan obsoletos, y el arnes rechaza el codigo generado
        // tambien por eso. Un aviso de obsolescencia en codigo que generamos
        // nosotros es un defecto de plantilla, no ruido del analizador.
        localeId: _idiomaElegido,
        pauseFor: const Duration(seconds: 3),
        listenFor: duracion,
        // ------------------------------------------------ REGLA 1
        //
        // onDevice EN FALSO, y es lo contrario de lo que dice el instinto.
        //
        // Poner onDevice: true parece la forma de "asegurar" que funcione sin
        // internet. Hace lo contrario: apunta a OTRO motor de reconocimiento,
        // distinto del que tiene descargado el modelo de español, y falla con
        // error_language_unavailable. En falso, y sin red, el sistema usa el
        // modelo instalado y anda.
        onDevice: false,
        // ------------------------------------------------ REGLA 2
        //
        // Los parciales son OBLIGATORIOS. Ver [_alRecibir].
        partialResults: true,
        listenMode: ListenMode.dictation,
      ),
    );

    await Future<void>.delayed(duracion);
    await _stt.stop();
    return _ultimoParcial;
  }

  // -------------------------------------------------------- REGLA 2
  //
  // SE GUARDA EL ULTIMO PARCIAL NO VACIO, NUNCA SOLO EL RESULTADO FINAL.
  //
  // Esta es la que rompe la implementacion obvia. Sin conexion, el
  // reconocedor emite resultados parciales CON el texto y, al cerrar la
  // sesion, un resultado final VACIO. Un clasificador que escuche unicamente
  // finalResult no recibe absolutamente nada: la app se queda muda y parece
  // que el microfono no funcionara.
  //
  // Se midio en un telefono real en modo avion. Es la razon por la que ese
  // experimento existio.
  //
  // SI ESTAS LEYENDO ESTO PARA "SIMPLIFICARLO": lo obvio es quedarse solo con
  // el resultado final y borrar esta acumulacion. Eso rompe la funcion entera
  // sin conexion, y no lo detecta ningun test — el codigo sigue compilando y
  // analizando perfecto. Solo se descubre con un telefono en modo avion.
  void _alRecibir(SpeechRecognitionResult resultado) {
    final String texto = resultado.recognizedWords.trim();
    if (texto.isNotEmpty) {
      _ultimoParcial = texto;
    }
  }

  Future<void> detener() async {
    await _stt.stop();
  }

  /// Confirmacion hablada. Es TTS: sintetiza, no reconoce.
  Future<void> decir(String texto) async {
    await _tts.speak(texto);
  }
}

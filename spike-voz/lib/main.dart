// Spike B-2 — ModelCollab · STT móvil OFFLINE
// Objetivo único: probar en un teléfono Android REAL, con MODO AVIÓN activado,
// que el reconocedor nativo transcribe español sin red.
// La UI es fea a propósito: está diseñada para que UNA captura de pantalla
// sirva como evidencia (estado de red + soporte on-device + texto transcrito).

import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart';

const fraseDePrueba = 'agenda una cita para mañana a las diez';

void main() => runApp(const SpikeVozApp());

class SpikeVozApp extends StatelessWidget {
  const SpikeVozApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Spike B-2 · STT offline',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const PantallaSpike(),
    );
  }
}

class PantallaSpike extends StatefulWidget {
  const PantallaSpike({super.key});

  @override
  State<PantallaSpike> createState() => _PantallaSpikeState();
}

class _PantallaSpikeState extends State<PantallaSpike> {
  final _speech = SpeechToText();
  final _connectivity = Connectivity();
  StreamSubscription<List<ConnectivityResult>>? _subRed;

  bool _inicializado = false;
  bool _escuchando = false;
  bool _forzarOnDevice = true;

  String _texto = '';
  String _estado = 'sin iniciar';
  String _error = '';
  String _localeElegido = '(sin elegir)';
  String _localesEs = '—';
  List<String> _localesEsIds = const [];
  List<ConnectivityResult> _red = const [];
  Duration? _latencia;
  DateTime? _inicioEscucha;
  final List<String> _bitacora = [];

  @override
  void initState() {
    super.initState();
    _arrancar();
  }

  @override
  void dispose() {
    _subRed?.cancel();
    super.dispose();
  }

  void _log(String linea) {
    final t = TimeOfDay.now();
    _bitacora.insert(
      0,
      '${t.hour.toString().padLeft(2, '0')}:'
          '${t.minute.toString().padLeft(2, '0')}  $linea',
    );
    if (_bitacora.length > 10) _bitacora.removeLast();
  }

  Future<void> _arrancar() async {
    _red = await _connectivity.checkConnectivity();
    _subRed = _connectivity.onConnectivityChanged.listen((r) {
      if (mounted) setState(() => _red = r);
    });

    // speech_to_text.initialize() pide RECORD_AUDIO por si mismo.
    final ok = await _speech.initialize(
      onStatus: (s) => setState(() {
        _estado = s;
        _escuchando = _speech.isListening;
        _log('status: $s');
      }),
      onError: (e) => setState(() {
        _error = '${e.errorMsg} (permanente: ${e.permanent})';
        _escuchando = false;
        _log('ERROR: ${e.errorMsg}');
      }),
      debugLogging: true,
    );

    var locale = '(ninguno en español)';
    var resumenEs = '—';
    var esIds = <String>[];
    if (ok) {
      final locales = await _speech.locales();
      final es =
          locales.where((l) => l.localeId.toLowerCase().startsWith('es')).toList();
      esIds = es.map((l) => l.localeId).toList();
      resumenEs = es.isEmpty
          ? 'NINGUNO (falta el paquete de español)'
          : es.map((l) => l.localeId).join(', ');
      if (es.isNotEmpty) {
        // OJO: el orden NO es por cercania dialectal, es por probabilidad de
        // tener modelo OFFLINE descargado. En el dispositivo de prueba estaba
        // es_US (74 MB) y NO es_ES, y pedir es_ES devuelve
        // error_language_unavailable aunque locales() lo liste.
        var elegido = es.first;
        for (final pref in ['es_US', 'es-US', 'es_BO', 'es-BO', 'es_MX', 'es-MX', 'es_ES', 'es-ES']) {
          final m = es.where((l) => l.localeId == pref);
          if (m.isNotEmpty) {
            elegido = m.first;
            break;
          }
        }
        locale = elegido.localeId;
      }
      _log('inicializado; locales es: ${es.length}');
    } else {
      _log('initialize() devolvio false');
    }

    if (!mounted) return;
    setState(() {
      _inicializado = ok;
      _localeElegido = locale;
      _localesEs = resumenEs;
      _localesEsIds = esIds;
    });
  }

  Future<void> _alternarEscucha() async {
    if (_escuchando) {
      await _speech.stop();
      setState(() => _escuchando = false);
      return;
    }
    setState(() {
      _texto = '';
      _error = '';
      _latencia = null;
      _inicioEscucha = DateTime.now();
      _escuchando = true;
      _log('listen(onDevice: $_forzarOnDevice, locale: $_localeElegido)');
    });

    await _speech.listen(
      onResult: (r) {
        setState(() {
          // El reconocedor OFFLINE emite el texto en los resultados PARCIALES
          // y luego manda un resultado final VACIO. Pisar el texto con ese
          // vacio borraba la evidencia justo al terminar de dictar.
          if (r.recognizedWords.isNotEmpty) {
            _texto = r.recognizedWords;
          }
          if (_inicioEscucha != null) {
            _latencia = DateTime.now().difference(_inicioEscucha!);
          }
          if (r.finalResult) {
            _log('final: ${r.recognizedWords.length} chars '
                '(retenido: ${_texto.length})');
            // Marca en logcat para poder disparar la captura de evidencia justo
            // cuando hay texto final, y para dejar registro textual del
            // resultado exacto sin depender de leer la imagen.
            debugPrint(
              'SPIKE_FINAL|avion=$_sinRed|onDevice=$_forzarOnDevice'
              '|locale=$_localeElegido|ms=${_latencia?.inMilliseconds}'
              '|texto=$_texto',
            );
          }
        });
      },
      listenOptions: SpeechListenOptions(
        onDevice: _forzarOnDevice,
        partialResults: true,
        cancelOnError: false,
        listenMode: ListenMode.dictation,
        localeId: _localeElegido.startsWith('es') ? _localeElegido : null,
        listenFor: const Duration(seconds: 30),
        pauseFor: const Duration(seconds: 4),
      ),
    );
  }

  bool get _sinRed =>
      _red.isEmpty ||
      (_red.length == 1 && _red.first == ConnectivityResult.none);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey.shade100,
      appBar: AppBar(
        title: const Text('SPIKE B-2 · STT OFFLINE'),
        backgroundColor: Colors.indigo,
        foregroundColor: Colors.white,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _bannerRed(),
              const SizedBox(height: 8),
              _panelDiagnostico(),
              const SizedBox(height: 10),
              const Text(
                'DICTAR EXACTAMENTE:',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
              ),
              const Text(
                '"$fraseDePrueba"',
                style: TextStyle(fontSize: 16, fontStyle: FontStyle.italic),
              ),
              const SizedBox(height: 10),
              _cajaTranscripcion(),
              const SizedBox(height: 10),
              SwitchListTile(
                tileColor: Colors.white,
                value: _forzarOnDevice,
                onChanged:
                    _escuchando ? null : (v) => setState(() => _forzarOnDevice = v),
                title: const Text(
                  'Forzar onDevice: true',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                subtitle: const Text(
                  'ON = solo reconocimiento local (falla si no hay modelo).\n'
                  'OFF = el sistema puede usar la nube si quiere.',
                ),
              ),
              const SizedBox(height: 10),
              _selectorLocale(),
              const SizedBox(height: 10),
              _botonMicrofono(),
              const SizedBox(height: 10),
              _bitacoraWidget(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _bannerRed() {
    final sinRed = _sinRed;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
      color: sinRed ? Colors.green.shade700 : Colors.red.shade700,
      child: Row(
        children: [
          Icon(
            sinRed ? Icons.airplanemode_active : Icons.wifi,
            color: Colors.white,
            size: 34,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  sinRed ? 'SIN RED — MODO AVIÓN OK' : 'HAY RED — EL SPIKE NO VALE',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 19,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  'connectivity: ${_red.map((e) => e.name).join(", ")}',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _panelDiagnostico() {
    Widget fila(String k, String v) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 120,
                child: Text(
                  k,
                  style:
                      const TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
                ),
              ),
              Expanded(child: Text(v, style: const TextStyle(fontSize: 12))),
            ],
          ),
        );

    return Container(
      color: Colors.white,
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          fila('initialize()', _inicializado ? 'true' : 'false'),
          fila('onDevice pedido', '$_forzarOnDevice'),
          fila('locales es', _localesEs),
          fila('localeId usado', _localeElegido),
          fila('estado', _estado),
          fila('latencia', _latencia == null ? '—' : '${_latencia!.inMilliseconds} ms'),
          if (_error.isNotEmpty) fila('ERROR', _error),
        ],
      ),
    );
  }

  Widget _cajaTranscripcion() {
    return Container(
      width: double.infinity,
      constraints: const BoxConstraints(minHeight: 140),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        border: Border.all(color: Colors.indigo, width: 3),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'TEXTO TRANSCRITO',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Text(
            _texto.isEmpty ? '(vacío)' : _texto,
            style: TextStyle(
              fontSize: 28,
              height: 1.2,
              fontWeight: FontWeight.bold,
              color: _texto.isEmpty ? Colors.grey : Colors.black,
            ),
          ),
        ],
      ),
    );
  }

  // El modelo offline descargado puede ser de un locale distinto al que uno
  // esperaria. Elegirlo a mano es la unica forma de probar cual responde.
  Widget _selectorLocale() {
    if (_localesEsIds.length < 2) return const SizedBox.shrink();
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'LOCALE A USAR (probar cual tiene modelo offline)',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            children: _localesEsIds
                .map(
                  (id) => ChoiceChip(
                    label: Text(id, style: const TextStyle(fontSize: 15)),
                    selected: _localeElegido == id,
                    onSelected: _escuchando
                        ? null
                        : (_) => setState(() => _localeElegido = id),
                  ),
                )
                .toList(),
          ),
        ],
      ),
    );
  }

  Widget _botonMicrofono() {
    return SizedBox(
      height: 105,
      child: ElevatedButton.icon(
        onPressed: _inicializado ? _alternarEscucha : null,
        style: ElevatedButton.styleFrom(
          backgroundColor: _escuchando ? Colors.red.shade700 : Colors.indigo,
          foregroundColor: Colors.white,
        ),
        icon: Icon(_escuchando ? Icons.stop : Icons.mic, size: 44),
        label: Text(
          _escuchando ? 'ESCUCHANDO…' : 'TOCAR Y DICTAR',
          style: const TextStyle(fontSize: 19, fontWeight: FontWeight.bold),
        ),
      ),
    );
  }

  Widget _bitacoraWidget() {
    return Container(
      width: double.infinity,
      color: Colors.black,
      padding: const EdgeInsets.all(8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'BITÁCORA',
            style: TextStyle(color: Colors.greenAccent, fontSize: 11),
          ),
          ..._bitacora.map(
            (l) => Text(
              l,
              style: const TextStyle(
                color: Colors.greenAccent,
                fontSize: 11,
                fontFamily: 'monospace',
              ),
            ),
          ),
        ],
      ),
    );
  }
}

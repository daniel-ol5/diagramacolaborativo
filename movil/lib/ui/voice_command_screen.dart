import 'package:flutter/material.dart';

import '../services/ai_command_service.dart';
import '../services/config_service.dart';
import '../voz/servicio_de_voz.dart';

enum _Estado { iniciando, listo, escuchando, revisando, enviando, resultado, error }

/// Dicta -> texto -> se manda a /ai-command. Nunca se manda audio: el
/// servidor no distingue si el texto vino tipeado en la web o dictado
/// aca, y no tiene por que -- es el mismo endpoint que ya usa
/// AiCommandBox.tsx.
class VoiceCommandScreen extends StatefulWidget {
  const VoiceCommandScreen({super.key, required this.sesion, required this.diagramaId});

  final Sesion sesion;
  final String diagramaId;

  @override
  State<VoiceCommandScreen> createState() => _VoiceCommandScreenState();
}

class _VoiceCommandScreenState extends State<VoiceCommandScreen> {
  final _voz = ServicioDeVoz();
  _Estado _estado = _Estado.iniciando;
  String _texto = '';
  AiCommandResult? _resultado;
  String? _error;

  @override
  void initState() {
    super.initState();
    _voz.iniciar().then((ok) {
      if (!mounted) return;
      setState(() => _estado = ok ? _Estado.listo : _Estado.error);
      if (!ok) _error = 'No se pudo iniciar el reconocimiento de voz en este teléfono.';
    });
  }

  @override
  void dispose() {
    _voz.detener();
    super.dispose();
  }

  Future<void> _escuchar() async {
    setState(() => _estado = _Estado.escuchando);
    final texto = await _voz.escuchar();
    if (!mounted) return;
    setState(() {
      _texto = texto;
      _estado = _Estado.revisando;
    });
  }

  Future<void> _enviar() async {
    setState(() => _estado = _Estado.enviando);
    try {
      final resultado = await AiCommandService.enviarComando(
        direccionServidor: widget.sesion.direccionServidor,
        token: widget.sesion.token,
        diagramId: widget.diagramaId,
        command: _texto,
        clientCommandId: DateTime.now().microsecondsSinceEpoch.toString(),
      );
      if (!mounted) return;
      setState(() {
        _resultado = resultado;
        _estado = _Estado.resultado;
      });
      await _voz.decir(resultado.message);
    } on AiCommandException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.mensaje;
        _estado = _Estado.error;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Dictar un comando')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: _contenido(),
        ),
      ),
    );
  }

  Widget _contenido() {
    switch (_estado) {
      case _Estado.iniciando:
        return const CircularProgressIndicator();

      case _Estado.listo:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.mic, size: 64),
            const SizedBox(height: 16),
            const Text('Tocá para empezar a dictar (hasta 8 segundos).', textAlign: TextAlign.center),
            const SizedBox(height: 24),
            FilledButton(onPressed: _escuchar, child: const Text('Empezar')),
          ],
        );

      case _Estado.escuchando:
        return const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.mic, size: 64, color: Colors.red),
            SizedBox(height: 16),
            Text('Escuchando…'),
          ],
        );

      case _Estado.revisando:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Se entendió:'),
            const SizedBox(height: 8),
            Text(
              _texto.isEmpty ? '(no se entendió nada)' : '"$_texto"',
              style: Theme.of(context).textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                OutlinedButton(onPressed: _escuchar, child: const Text('Repetir')),
                const SizedBox(width: 12),
                FilledButton(onPressed: _texto.isEmpty ? null : _enviar, child: const Text('Enviar')),
              ],
            ),
          ],
        );

      case _Estado.enviando:
        return const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Enviando…'),
          ],
        );

      case _Estado.resultado:
        final r = _resultado!;
        // Mismo criterio que CU16 en la web: el texto del servidor se
        // muestra TAL CUAL, nunca reescrito, y un rechazo del
        // guardarrail (200, applied:false) NO se pinta como error --
        // es el asistente funcionando bien, no una falla.
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              r.applied ? Icons.check_circle : Icons.info_outline,
              size: 48,
              color: r.applied ? Colors.green : null,
            ),
            const SizedBox(height: 12),
            Text(r.message, textAlign: TextAlign.center),
            const SizedBox(height: 24),
            FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Listo')),
          ],
        );

      case _Estado.error:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48, color: Colors.red),
            const SizedBox(height: 12),
            Text(_error ?? 'Ocurrió un error.', textAlign: TextAlign.center),
            const SizedBox(height: 24),
            FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Volver')),
          ],
        );
    }
  }
}

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:movil/models/project.dart';
import 'package:movil/services/chat_service.dart';
import 'package:movil/services/config_service.dart';
import 'package:movil/ui/chat_screen.dart';
import 'package:movil/ui/diagram_list_screen.dart';

// CU23: contador de mensajes nuevos en el icono de chat de DiagramListScreen.
// La lista de diagramas usa ApiService con HTTP real: en los tests el
// binding contesta 400 y se ve su ErrorView; aca solo importa el icono.

const _sesion = Sesion(
  token: 't',
  userId: 'u1',
  email: 'ana@x.com',
  nombreCompleto: 'Ana',
  direccionServidor: 'http://servidor',
);
const _proyecto = Project(id: 'p1', name: 'Tienda', description: null, role: 'OWNER');

http.Response _json(Object o) => http.Response(jsonEncode(o), 200, headers: {'content-type': 'application/json'});

/// Servidor falso con mensajes 1..[ultimo] de otra persona.
class Servidor {
  Servidor({required this.ultimo, required this.lastRead});

  int ultimo;
  int lastRead;
  bool falla = false;
  int gets = 0;

  int get sinLeer => ultimo - lastRead;

  Future<http.Response> responder(http.Request r) async {
    if (falla) throw http.ClientException('sin red');
    if (r.url.path.endsWith('/read')) {
      if (r.method == 'PUT') {
        final seq = (jsonDecode(r.body) as Map<String, dynamic>)['upToSeq'] as int;
        if (seq > lastRead) lastRead = seq > ultimo ? ultimo : seq;
      } else {
        gets++;
      }
      return _json({'lastReadSeq': lastRead, 'unreadCount': sinLeer, 'lastSeq': ultimo});
    }
    final q = r.url.queryParameters;
    if (q['afterSeq'] != null || q['beforeSeq'] != null) return _json({'messages': [], 'hasMore': false});
    return _json({
      'messages': [
        for (var i = 1; i <= ultimo; i++)
          {
            'id': 'm$i',
            'projectId': 'p1',
            'seq': i,
            'authorType': 'USER',
            'authorId': 'u2',
            'authorName': 'Beto',
            'kind': 'CHAT',
            'text': 'mensaje $i',
            'createdAt': DateTime.now().toUtc().toIso8601String(),
          },
      ],
      'hasMore': false,
    });
  }
}

Future<void> _abrir(WidgetTester tester, Servidor s) async {
  final servicio = ChatService(direccionServidor: 'http://servidor', token: 't', client: MockClient(s.responder));
  await tester.pumpWidget(
    MaterialApp(home: DiagramListScreen(sesion: _sesion, proyecto: _proyecto, chatServicio: servicio)),
  );
  for (var i = 0; i < 4; i++) {
    await tester.pump();
  }
}

Finder get _numero => find.descendant(of: find.byType(Badge), matching: find.byType(Text));

String? _textoDelBadge(WidgetTester tester) {
  final badge = tester.widget<Badge>(find.byType(Badge));
  if (!badge.isLabelVisible) return null;
  return tester.widget<Text>(_numero).data;
}

/// testWidgets que al final deja vencer el timeout (10 s) de la lista de
/// diagramas, para no dejar temporizadores pendientes.
void prueba(String descripcion, Future<void> Function(WidgetTester tester) cuerpo) {
  testWidgets(descripcion, (tester) async {
    await cuerpo(tester);
    await tester.pump(const Duration(seconds: 11));
  });
}

void main() {
  prueba('al abrir muestra el numero de mensajes nuevos y TalkBack lo lee', (tester) async {
    await _abrir(tester, Servidor(ultimo: 5, lastRead: 2));
    expect(_textoDelBadge(tester), '3');
    expect(find.byTooltip('Chat del proyecto, 3 mensajes nuevos'), findsOneWidget);
  });

  prueba('sin mensajes nuevos no hay numero', (tester) async {
    await _abrir(tester, Servidor(ultimo: 5, lastRead: 5));
    expect(_textoDelBadge(tester), isNull);
    expect(find.byTooltip('Chat del proyecto'), findsOneWidget);
  });

  prueba('mas de 99: "99+"', (tester) async {
    await _abrir(tester, Servidor(ultimo: 150, lastRead: 0));
    expect(_textoDelBadge(tester), '99+');
    expect(find.byTooltip('Chat del proyecto, más de 99 mensajes nuevos'), findsOneWidget);
  });

  prueba('si falla, sin numero y sin avisos', (tester) async {
    await _abrir(tester, Servidor(ultimo: 5, lastRead: 2)..falla = true);
    expect(_textoDelBadge(tester), isNull);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.byTooltip('Chat del proyecto'), findsOneWidget);
  });

  prueba('al volver del chat (habiendo leido todo) el numero desaparece', (tester) async {
    final s = Servidor(ultimo: 5, lastRead: 2);
    await _abrir(tester, s);
    expect(_textoDelBadge(tester), '3');

    await tester.tap(find.byTooltip('Chat del proyecto, 3 mensajes nuevos'));
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.byType(ChatScreen), findsOneWidget);
    expect(find.text('Mensajes nuevos'), findsOneWidget);

    // Al final y en primer plano: se marca como leido.
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(s.lastRead, 5);

    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.byType(ChatScreen), findsNothing);
    expect(_textoDelBadge(tester), isNull);
    expect(find.byTooltip('Chat del proyecto'), findsOneWidget);
  });

  prueba('un GET viejo que llega tarde no vuelve a poner el numero', (tester) async {
    final s = Servidor(ultimo: 5, lastRead: 2);
    await _abrir(tester, s);
    // Se abre y se cierra el chat ENSEGUIDA: el PUT de salida y el GET de
    // "al volver" salen juntos.
    await tester.tap(find.byTooltip('Chat del proyecto, 3 mensajes nuevos'));
    for (var i = 0; i < 6; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    for (var i = 0; i < 12; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(s.lastRead, 5, reason: 'el PUT de salida llego');
    expect(_textoDelBadge(tester), isNull);
  });
}

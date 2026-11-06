import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:movil/models/project.dart';
import 'package:movil/services/config_service.dart';
import 'package:movil/ui/chat_screen.dart';
import 'package:movil/ui/diagram_list_screen.dart';

void main() {
  testWidgets('el icono de chat de DiagramListScreen abre ChatScreen con el proyecto', (tester) async {
    // Sin servidor: el binding de test contesta 400 a todo HTTP real, asi
    // que las dos pantallas muestran su error. Aca solo importa la navegacion.
    const sesion = Sesion(
      token: 't',
      userId: 'u1',
      email: 'ana@x.com',
      nombreCompleto: 'Ana',
      direccionServidor: 'http://servidor',
    );
    const proyecto = Project(id: 'p1', name: 'Tienda', description: null, role: 'OWNER');
    await tester.pumpWidget(const MaterialApp(home: DiagramListScreen(sesion: sesion, proyecto: proyecto)));
    await tester.pump();

    final boton = find.byTooltip('Chat del proyecto');
    expect(boton, findsOneWidget);
    // El icono mide 40 dp; Material le agrega margen tactil hasta 48 dp.
    await expectLater(tester, meetsGuideline(androidTapTargetGuideline));

    await tester.tap(boton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    final chat = tester.widget<ChatScreen>(find.byType(ChatScreen));
    expect(chat.projectId, 'p1');
    expect(chat.projectName, 'Tienda');
    expect(chat.sesion.userId, 'u1');

    // Deja vencer cualquier timeout de red pendiente antes de terminar.
    await tester.pump(const Duration(seconds: 11));
  });
}

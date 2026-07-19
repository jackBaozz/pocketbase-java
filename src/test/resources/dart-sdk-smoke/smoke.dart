import 'dart:async';

import 'package:http/http.dart' as http;
import 'package:pocketbase/pocketbase.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    throw StateError('Usage: dart run smoke.dart <baseUrl>');
  }

  final pb = PocketBase(args.first);
  await pb.send('/api/bootstrap/superuser', method: 'POST', body: {
    'email': 'root@example.com',
    'password': 'secret123',
  });
  await pb.collection('_superusers').authWithPassword('root@example.com', 'secret123');
  await pb.collection('_superusers').authRefresh();
  if (!pb.authStore.isValid) {
    throw StateError('Dart SDK auth store rejected the refreshed token');
  }

  final collectionName = 'dart_smoke_posts';
  await pb.send('/api/collections', method: 'POST', body: {
    'name': collectionName,
    'type': 'base',
    'listRule': '',
    'viewRule': '',
    'createRule': '',
    'updateRule': '',
    'deleteRule': '',
    'fields': [
      {'name': 'title', 'type': 'text', 'required': true},
      {'name': 'count', 'type': 'number'},
      {'name': 'attachment', 'type': 'file'},
    ],
  });

  final created = await pb.collection(collectionName).create(body: {
    'title': 'from dart',
    'count': 1,
  });
  final updated = await pb.collection(collectionName).update(created.id, body: {
    'title': 'from dart updated',
    'count': 2,
  });
  if (updated.get<String>('title') != 'from dart updated') {
    throw StateError('record update did not roundtrip through Dart SDK');
  }

  final list = await pb.collection(collectionName).getList(page: 1, perPage: 10);
  if (list.items.isEmpty) {
    throw StateError('Dart SDK list returned no records');
  }

  final fileRecord = await pb.collection(collectionName).create(
    body: {'title': 'dart file', 'count': 3},
    files: [
      http.MultipartFile.fromString(
        'attachment',
        'hello from dart',
        filename: 'dart-smoke.txt',
      ),
    ],
  );
  final filename = fileRecord.get<String>('attachment');
  final fileResponse = await http.get(pb.files.getURL(fileRecord, filename));
  if (fileResponse.statusCode != 200 || fileResponse.body != 'hello from dart') {
    throw StateError('Dart SDK file upload/download did not roundtrip');
  }
  if ((await pb.files.getToken()).isEmpty) {
    throw StateError('Dart SDK did not receive a protected file token');
  }

  final batch = pb.createBatch()
    ..collection(collectionName).create(body: {
      'title': 'dart batch one',
      'count': 4,
    })
    ..collection(collectionName).create(body: {
      'title': 'dart batch two',
      'count': 5,
    });
  final batchResult = await batch.send();
  if (batchResult.length != 2 || batchResult.any((item) => item.status >= 400)) {
    throw StateError('Dart SDK batch response did not match the official array contract');
  }

  final realtimeEvent = Completer<RecordSubscriptionEvent>();
  final unsubscribe = await pb.collection(collectionName).subscribe('*', (event) {
    if (!realtimeEvent.isCompleted &&
        event.action == 'create' &&
        event.record?.get<String>('title') == 'dart realtime') {
      realtimeEvent.complete(event);
    }
  });
  final realtimeRecord = await pb.collection(collectionName).create(body: {
    'title': 'dart realtime',
    'count': 6,
  });
  final event = await realtimeEvent.future.timeout(const Duration(seconds: 10));
  if (event.record?.id != realtimeRecord.id) {
    throw StateError('Dart SDK realtime event returned the wrong record');
  }
  await unsubscribe();

  await pb.collection(collectionName).delete(created.id);
  await pb.collection(collectionName).delete(fileRecord.id);
  for (final item in await pb.collection(collectionName).getFullList()) {
    await pb.collection(collectionName).delete(item.id);
  }
  print('Dart SDK Smoke Test Passed!');
}

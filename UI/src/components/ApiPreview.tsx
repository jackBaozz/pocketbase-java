import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Check, Copy, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useDrawerTransition } from "./useDrawerTransition";
import { useModalInteraction } from "./useModalInteraction";
import "./ApiPreview.css";

/* -------------------------------------------------------------------------- */
/* public api                                                                  */
/* -------------------------------------------------------------------------- */

export type ApiPreviewField = {
  name: string;
  type: string;
  required?: boolean;
  hidden?: boolean;
};

export type ApiPreviewCollection = {
  id: string;
  name: string;
  type: string;
  fields?: ApiPreviewField[];
  passwordAuth?: { enabled?: boolean };
  otp?: { enabled?: boolean };
  mfa?: { enabled?: boolean };
  oauth2?: { enabled?: boolean };
};

export type ApiPreviewProps = {
  collection: ApiPreviewCollection;
  baseUrl: string;
  onClose: () => void;
};

/* -------------------------------------------------------------------------- */
/* internal model                                                              */
/* -------------------------------------------------------------------------- */

type Translate = (key: string, fallback: string) => string;

type Sdk = "js" | "dart" | "curl";

/** JS/Dart samples during build; curl is attached in finalizeEndpoints. */
type SdkPair = { js: string; dart: string };

type ParamRow = {
  name: string;
  type: string;
  requirement?: "required" | "optional";
  description: ReactNode;
};

type ParamTable = {
  heading: string;
  rows: ParamRow[];
};

type ResponseSample = {
  status: string;
  body: string;
};

/** One action under a grouped nav item (e.g. Request / Confirm email change). */
type EndpointVariantBase = {
  id: string;
  tab: string;
  description: ReactNode[];
  method: string;
  path: string;
  note?: string;
  tables: ParamTable[];
  responses: ResponseSample[];
};

type EndpointVariantDraft = EndpointVariantBase & { sdk: SdkPair };
type EndpointVariant = EndpointVariantBase & { sdk: Record<Sdk, string> };

type EndpointBase = {
  id: string;
  nav: string;
  description: ReactNode[];
  method: string;
  path: string;
  note?: string;
  tables: ParamTable[];
  responses: ResponseSample[];
  enabled: boolean;
  /** renders a divider above the nav entry (start of a new endpoint group) */
  divider?: boolean;
};

type Endpoint = EndpointBase & {
  sdk: Record<Sdk, string>;
  /** Official groups Verification / Password reset / Email change / OTP under one nav item. */
  variants?: EndpointVariant[];
};

type EndpointDraft = EndpointBase & {
  sdk: SdkPair;
  variants?: EndpointVariantDraft[];
};

const SDK_STORAGE_KEY = "pbLastSDK";
const SDK_CHANGE_EVENT = "pb-last-sdk-change";

/** Official order: records first, then auth (grouped request/confirm as single nav rows). */
const NAV_ORDER = [
  "list",
  "view",
  "create",
  "update",
  "delete",
  "realtime",
  "batch",
  "auth-methods",
  "auth-with-password",
  "auth-with-oauth2",
  "auth-with-otp",
  "auth-refresh",
  "verification",
  "password-reset",
  "email-change",
  "impersonate"
] as const;

const SDK_TABS: ReadonlyArray<{ id: Sdk; label: string }> = [
  { id: "js", label: "JS SDK" },
  { id: "dart", label: "Dart SDK" },
  { id: "curl", label: "curl" }
];

/* -------------------------------------------------------------------------- */
/* text helpers                                                                */
/* -------------------------------------------------------------------------- */

function dedent(value: string): string {
  const lines = value.replace(/\t/g, "  ").split("\n");
  while (lines.length > 0 && lines[0].trim() === "") lines.shift();
  while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();

  let pad = Number.POSITIVE_INFINITY;
  for (const line of lines) {
    if (!line.trim()) continue;
    pad = Math.min(pad, line.length - line.trimStart().length);
  }
  if (!Number.isFinite(pad) || pad === 0) return lines.join("\n");

  return lines.map((line) => line.slice(pad)).join("\n");
}

/**
 * Tagged template for code samples: keeps multi-line interpolations aligned
 * with the line they are injected into and strips the source indentation.
 */
function code(strings: TemplateStringsArray, ...values: Array<string | number>): string {
  let out = "";
  strings.forEach((chunk, index) => {
    out += chunk;
    if (index >= values.length) return;

    const currentLine = out.slice(out.lastIndexOf("\n") + 1);
    const indent = " ".repeat(currentLine.length - currentLine.trimStart().length);
    out += String(values[index]).split("\n").join("\n" + indent);
  });
  return dedent(out);
}

function json(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

function apiError(status: number, message: string, data: Record<string, unknown> = {}): ResponseSample {
  return { status: String(status), body: json({ status, message, data }) };
}

function requiredError(field: string): Record<string, unknown> {
  return { [field]: { code: "validation_required", message: "Missing required value." } };
}

/** Official-style curl sample (Authorization header + optional -X / -d). */
function formatCurl(opts: { method?: string; url: string; body?: string }): string {
  const lines = ["curl \\"];
  const method = (opts.method ?? "GET").toUpperCase();
  if (method !== "GET") {
    lines.push(`  -X ${method} \\`);
  }
  lines.push(`  -H 'Authorization:TOKEN' \\`);
  if (opts.body !== undefined) {
    lines.push(`  -H 'Content-Type: application/json' \\`);
    lines.push(`  -d '${opts.body.replace(/'/g, `'\\''`)}' \\`);
  }
  lines.push(`  '${opts.url}'`);
  return lines.join("\n");
}

function generateCurl(
  endpoint: { id: string; method: string; path: string },
  baseUrl: string,
  collectionName: string,
  bodies: { create: string; update: string }
): string {
  const base = baseUrl.replace(/\/$/, "");
  const path = endpoint.path.replace(/:id/g, "RECORD_ID");
  const url = `${base}${path}`;
  const root = `/api/collections/${collectionName}`;

  switch (endpoint.id) {
    case "list":
      return formatCurl({ url: `${url}?perPage=50` });
    case "view":
    case "auth-methods":
    case "auth-refresh":
      return formatCurl({ url });
    case "create":
      return formatCurl({ method: "POST", url, body: bodies.create });
    case "update":
      return formatCurl({ method: "PATCH", url, body: bodies.update });
    case "delete":
      return formatCurl({ method: "DELETE", url });
    case "realtime":
      return formatCurl({
        method: "POST",
        url: `${base}/api/realtime`,
        body: JSON.stringify({
          clientId: "CLIENT_ID",
          subscriptions: [`${collectionName}/*`]
        })
      });
    case "batch":
      return formatCurl({
        method: "POST",
        url: `${base}/api/batch`,
        body: JSON.stringify({
          requests: [
            { method: "POST", url: `${root}/records`, body: {} },
            { method: "PATCH", url: `${root}/records/RECORD_ID`, body: {} },
            { method: "DELETE", url: `${root}/records/RECORD_ID` }
          ]
        })
      });
    case "auth-with-password":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ identity: "YOUR_EMAIL_OR_USERNAME", password: "YOUR_PASSWORD" })
      });
    case "auth-with-oauth2":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({
          provider: "google",
          code: "CODE",
          codeVerifier: "CODE_VERIFIER",
          redirectUrl: "REDIRECT_URL"
        })
      });
    case "request-verification":
    case "request-password-reset":
    case "request-otp":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ email: "test@example.com" })
      });
    case "confirm-verification":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ token: "VERIFICATION_TOKEN" })
      });
    case "confirm-password-reset":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({
          token: "PASSWORD_RESET_TOKEN",
          password: "12345678",
          passwordConfirm: "12345678"
        })
      });
    case "request-email-change":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ newEmail: "new@example.com" })
      });
    case "confirm-email-change":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ token: "EMAIL_CHANGE_TOKEN", password: "12345678" })
      });
    case "auth-with-otp":
    case "auth-with-otp-action":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ otpId: "OTP_ID", password: "OTP_PASSWORD" })
      });
    case "impersonate":
      return formatCurl({
        method: "POST",
        url,
        body: JSON.stringify({ duration: 3600 })
      });
    default: {
      const method = endpoint.method.split("/")[0].toUpperCase();
      if (method === "GET" || method === "HEAD") return formatCurl({ url });
      if (method === "DELETE") return formatCurl({ method: "DELETE", url });
      return formatCurl({ method, url, body: "{}" });
    }
  }
}

/**
 * Official nav order for record + auth endpoints.
 * Also attaches curl samples next to JS/Dart SDK snippets (including grouped variants).
 */
function finalizeEndpoints(
  drafts: EndpointDraft[],
  baseUrl: string,
  collectionName: string,
  bodies: { create: string; update: string }
): Endpoint[] {
  const rank = (id: string, fallback: number) => {
    const index = (NAV_ORDER as readonly string[]).indexOf(id);
    return index === -1 ? 1000 + fallback : index;
  };

  const withCurl = (
    draft: { id: string; method: string; path: string; sdk: SdkPair }
  ): Record<Sdk, string> => ({
    js: draft.sdk.js,
    dart: draft.sdk.dart,
    curl: generateCurl(
      { id: draft.id, method: draft.method, path: draft.path },
      baseUrl,
      collectionName,
      bodies
    )
  });

  return [...drafts]
    .sort((a, b) => rank(a.id, drafts.indexOf(a)) - rank(b.id, drafts.indexOf(b)))
    .map((endpoint): Endpoint => {
      if (endpoint.variants && endpoint.variants.length > 0) {
        const variants: EndpointVariant[] = endpoint.variants.map((variant) => ({
          ...variant,
          sdk: withCurl(variant)
        }));
        const first = variants[0];
        return {
          id: endpoint.id,
          nav: endpoint.nav,
          enabled: endpoint.enabled,
          divider: endpoint.divider,
          description: first.description,
          method: first.method,
          path: first.path,
          note: first.note,
          tables: first.tables,
          responses: first.responses,
          sdk: first.sdk,
          variants
        };
      }
      return {
        id: endpoint.id,
        nav: endpoint.nav,
        enabled: endpoint.enabled,
        divider: endpoint.divider,
        description: endpoint.description,
        method: endpoint.method,
        path: endpoint.path,
        note: endpoint.note,
        tables: endpoint.tables,
        responses: endpoint.responses,
        sdk: withCurl(endpoint)
      };
    });
}

/* -------------------------------------------------------------------------- */
/* dummy record data (mirrors the official api preview samples)                */
/* -------------------------------------------------------------------------- */

const FALLBACK_FIELDS: ApiPreviewField[] = [
  { name: "id", type: "text" },
  { name: "created", type: "autodate" },
  { name: "updated", type: "autodate" }
];

const TYPE_LABELS: Record<string, string> = {
  text: "String",
  editor: "String",
  email: "String",
  url: "String",
  date: "String",
  autodate: "String",
  password: "String",
  select: "String",
  relation: "String",
  number: "Number",
  bool: "Boolean",
  file: "File",
  json: "Mixed",
  geoPoint: "Object"
};

const SAMPLE_DATE = "2022-01-01 10:00:00.123Z";
const FILE_PLACEHOLDER = "[[new File([], 'filename.txt')]]";

function dummyFieldValue(field: ApiPreviewField, forSubmit: boolean): unknown {
  switch (field.type) {
    case "number":
      return 123.456;
    case "bool":
      return true;
    case "email":
      return "test@example.com";
    case "url":
      return "https://example.com";
    case "editor":
      return "Lorem ipsum dolor sit amet...";
    case "date":
      return SAMPLE_DATE;
    case "autodate":
      return forSubmit ? undefined : SAMPLE_DATE;
    case "select":
      return "optionA";
    case "file":
      return forSubmit ? FILE_PLACEHOLDER : "filename.txt";
    case "relation":
      return "RELATION_RECORD_ID";
    case "json":
      return { example: 123 };
    case "geoPoint":
      return { lon: 0, lat: 0 };
    case "password":
      return undefined;
    case "text":
      return field.name === "id" ? "RECORD_ID" : "example text";
    default:
      return "example value";
  }
}

/** strips the `"[[ ... ]]"` markers so the JSON sample can hold JS expressions */
function unwrapPlaceholders(value: string): string {
  return value.replaceAll('"[[', "").replaceAll(']]"', "");
}

/* -------------------------------------------------------------------------- */
/* endpoint definitions                                                        */
/* -------------------------------------------------------------------------- */

function buildEndpoints(collection: ApiPreviewCollection, baseUrl: string, tr: Translate): Endpoint[] {
  const name = collection.name;
  const isAuth = collection.type === "auth";
  const isView = collection.type === "view";
  const root = `/api/collections/${name}`;
  // curl bodies are compact JSON (official style)

  const visibleFields = (collection.fields ?? []).filter((field) => !field.hidden);
  const recordFields = visibleFields.length > 0 ? visibleFields : FALLBACK_FIELDS;
  const authManagedNames = ["password", "passwordConfirm", "oldPassword", "tokenKey", "verified"];

  /* ---------------------------------------------------------------- samples */

  const dummyRecord = (): Record<string, unknown> => {
    const record: Record<string, unknown> = {
      collectionId: collection.id,
      collectionName: name
    };
    for (const field of recordFields) {
      const value = dummyFieldValue(field, false);
      if (value !== undefined) record[field.name] = value;
    }
    return record;
  };

  const submitPayload = (forUpdate: boolean, withComplexValues: boolean): Record<string, unknown> => {
    const payload: Record<string, unknown> = {};
    for (const field of recordFields) {
      if (field.name === "id" || field.type === "autodate" || field.type === "password") continue;
      if (isAuth && authManagedNames.includes(field.name)) continue;
      if (isAuth && forUpdate && field.name === "email") continue;
      if (!withComplexValues && ["file", "json", "geoPoint"].includes(field.type)) continue;

      const value = dummyFieldValue(field, true);
      if (value !== undefined) payload[field.name] = value;
    }
    if (isAuth) {
      if (forUpdate) payload.oldPassword = "12345678";
      payload.password = "12345678";
      payload.passwordConfirm = "12345678";
    }
    return payload;
  };

  const recordSample = json(dummyRecord());
  const authSample = json({ token: "...JWT...", record: dummyRecord() });

  /* ------------------------------------------------------------ shared docs */

  const bodyParams = tr("api_preview.body_params", "Body params");
  const queryParams = tr("api_preview.query_params", "?query params");
  const pathParams = tr("api_preview.path_params", "Path params");

  const expandInfo: ReactNode = (
    <>
      <p>{tr("api_preview.expand_intro", "Auto expand record relations. For example:")}</p>
      <pre className="apx-snippet">?expand=relField1,relField2.subRelField</pre>
      <p>
        {tr(
          "api_preview.expand_depth",
          'Supports up to 6-levels depth nested relations expansion. The expanded relations are appended to each record under the "expand" property.'
        )}
      </p>
      <p>
        {tr(
          "api_preview.expand_rules",
          "Only the relations the request user is allowed to view will be expanded."
        )}
      </p>
    </>
  );

  const fieldsInfo: ReactNode = (
    <>
      <p>
        {tr(
          "api_preview.fields_intro",
          "Comma separated string of the fields to return in the JSON response (by default returns all fields). For example:"
        )}
      </p>
      <pre className="apx-snippet">{"// return all root level fields and only\n// \"relField.someField\" from expand\n?fields=*,expand.relField.someField"}</pre>
      <p>
        {tr("api_preview.fields_wildcard", 'Use "*" to target all keys from the specific depth level.')}
      </p>
      <p>
        {tr(
          "api_preview.fields_modifiers",
          "The :excerpt(maxLength, withEllipsis?) modifier returns a short plain text version of a string field value."
        )}
      </p>
    </>
  );

  const expandRow: ParamRow = { name: "expand", type: "String", description: expandInfo };
  const fieldsRow: ParamRow = { name: "fields", type: "String", description: fieldsInfo };
  const expandFieldsTable: ParamTable = { heading: queryParams, rows: [expandRow, fieldsRow] };

  const idPathTable = (action: string): ParamTable => ({
    heading: pathParams,
    rows: [{ name: "id", type: "String", description: action }]
  });

  const bodyFieldRows = (forUpdate: boolean): ParamRow[] =>
    recordFields
      .filter((field) => field.type !== "autodate" && field.type !== "password")
      .filter((field) => !(forUpdate && field.name === "id"))
      .filter((field) => !(isAuth && [...authManagedNames, "email", "emailVisibility"].includes(field.name)))
      .map((field) => ({
        name: field.name,
        type: TYPE_LABELS[field.type] ?? "Mixed",
        requirement: field.required ? ("required" as const) : ("optional" as const),
        description: (
          <>
            <code>{field.type}</code> {tr("api_preview.field_value", "field type value.")}
          </>
        )
      }));

  const authBodyTable: ParamTable = {
    heading: bodyParams,
    rows: [
      {
        name: "email",
        type: "String",
        requirement: recordFields.find((f) => f.name === "email")?.required ? "required" : "optional",
        description: tr("api_preview.auth_email", "Auth record email address.")
      },
      {
        name: "emailVisibility",
        type: "Boolean",
        requirement: "optional",
        description: tr(
          "api_preview.auth_email_visibility",
          "Whether to show/hide the auth record email when fetching the record data. Superusers and the owner of the record always have access to the email address."
        )
      },
      {
        name: "password",
        type: "String",
        requirement: "required",
        description: tr("api_preview.auth_password", "Auth record password.")
      },
      {
        name: "passwordConfirm",
        type: "String",
        requirement: "required",
        description: tr("api_preview.auth_password_confirm", "Auth record password confirmation.")
      },
      {
        name: "verified",
        type: "Boolean",
        requirement: "optional",
        description: tr(
          "api_preview.auth_verified",
          'Indicates whether the auth record is verified or not. This field can be set only by superusers or auth records with "Manage" access.'
        )
      }
    ]
  };

  const superuserNote = tr("api_preview.requires_superuser", "Requires superuser Authorization:TOKEN header");
  const authNote = tr("api_preview.requires_auth", "Requires Authorization:TOKEN header");

  const sdkHeader = (lang: "js" | "dart"): string =>
    lang === "js"
      ? code`
        import PocketBase from 'pocketbase';

        const pb = new PocketBase('${baseUrl}');

        ...
      `
      : code`
        import 'package:pocketbase/pocketbase.dart';

        final pb = PocketBase('${baseUrl}');

        ...
      `;

  const sample = (lang: "js" | "dart", body: string): string => `${sdkHeader(lang)}\n\n${dedent(body)}`;

  const verificationHint = isAuth
    ? "\n\n" +
      code`
      // (optional) send an email verification request
      await pb.collection('${name}').requestVerification('test@example.com');
    `
    : "";

  /* --------------------------------------------------------------- records */

  const endpoints: EndpointDraft[] = [
    {
      id: "list",
      nav: tr("api_preview.nav.list", "List/Search"),
      description: [
        tr("api_preview.list.desc", "Fetch a paginated records list, supporting sorting and filtering.")
      ],
      method: "GET",
      path: `${root}/records`,
      enabled: true,
      sdk: {
        js: sample(
          "js",
          `
          // fetch a paginated records list
          const resultList = await pb.collection('${name}').getList(1, 50, {
            filter: 'someField1 != someField2',
          });

          // you can also fetch all records at once via getFullList
          const records = await pb.collection('${name}').getFullList({
            sort: '-someField',
          });

          // or fetch only the first record that matches the specified filter
          const record = await pb.collection('${name}').getFirstListItem(
            'someField="test"',
            { expand: 'relField1,relField2.subRelField' },
          );
          `
        ),
        dart: sample(
          "dart",
          `
          // fetch a paginated records list
          final resultList = await pb.collection('${name}').getList(
            page: 1,
            perPage: 50,
            filter: 'someField1 != someField2',
          );

          // you can also fetch all records at once via getFullList
          final records = await pb.collection('${name}').getFullList(
            sort: '-someField',
          );

          // or fetch only the first record that matches the specified filter
          final record = await pb.collection('${name}').getFirstListItem(
            'someField="test"',
            expand: 'relField1,relField2.subRelField',
          );
          `
        )
      },
      tables: [
        {
          heading: queryParams,
          rows: [
            {
              name: "page",
              type: "Number",
              description: tr(
                "api_preview.param.page",
                "The page (aka. offset) of the paginated list (default to 1)."
              )
            },
            {
              name: "perPage",
              type: "Number",
              description: tr(
                "api_preview.param.per_page",
                "Specify the max returned records per page (default to 30)."
              )
            },
            {
              name: "sort",
              type: "String",
              description: (
                <>
                  <p>
                    {tr(
                      "api_preview.param.sort",
                      "Specify the records order attribute(s). Add -/+ (default) in front of the attribute for DESC / ASC order."
                    )}
                  </p>
                  <pre className="apx-snippet">{"// DESC by created and ASC by id\n?sort=-created,id"}</pre>
                  <p>
                    {tr(
                      "api_preview.param.sort_special",
                      "In addition to the collection non-hidden fields, the following special sort fields could be also used:"
                    )}{" "}
                    <code>@random</code>
                    {isView ? null : (
                      <>
                        , <code>@rowid</code>
                      </>
                    )}
                    .
                  </p>
                </>
              )
            },
            {
              name: "filter",
              type: "String",
              description: (
                <>
                  <p>{tr("api_preview.param.filter", "Filter the returned records. For example:")}</p>
                  <pre className="apx-snippet">{"?filter=(id='abc' && created>'2022-01-01')"}</pre>
                  <p className="apx-hint">
                    {tr(
                      "api_preview.param.filter_encoding",
                      "All query params must be properly URL encoded (the SDKs do this automatically)."
                    )}
                  </p>
                  <p>
                    {tr(
                      "api_preview.param.filter_syntax",
                      "The filter syntax follows the OPERAND OPERATOR OPERAND format. Supported operators: =, !=, >, >=, <, <=, ~ (like), !~ (not like) and their any/at-least-one-of ?-prefixed variants. Group expressions with brackets, && (AND) and || (OR)."
                    )}
                  </p>
                </>
              )
            },
            expandRow,
            fieldsRow,
            {
              name: "skipTotal",
              type: "Boolean",
              description: (
                <>
                  <p>
                    {tr(
                      "api_preview.param.skip_total",
                      'If set to 1/true the total counts query will be skipped and the response fields "totalItems" and "totalPages" will have -1 value.'
                    )}
                  </p>
                  <p>
                    {tr(
                      "api_preview.param.skip_total_hint",
                      "This could drastically speed up the search queries when the total counters are not needed or cursor based pagination is used. It is set by default in the getFirstListItem() and getFullList() SDK methods."
                    )}
                  </p>
                </>
              )
            }
          ]
        }
      ],
      responses: [
        {
          status: "200",
          body: json({
            page: 1,
            perPage: 30,
            totalPages: 1,
            totalItems: 2,
            items: [dummyRecord(), dummyRecord()]
          })
        },
        apiError(400, "Something went wrong while processing your request."),
        apiError(403, "Only superusers can access this action.")
      ]
    },
    {
      id: "view",
      nav: tr("api_preview.nav.view", "View"),
      description: [tr("api_preview.view.desc", "Fetch a single record by its ID.")],
      method: "GET",
      path: `${root}/records/:id`,
      enabled: true,
      sdk: {
        js: sample(
          "js",
          `
          const record = await pb.collection('${name}').getOne('RECORD_ID', {
            expand: 'relField1,relField2.subRelField',
          });
          `
        ),
        dart: sample(
          "dart",
          `
          final record = await pb.collection('${name}').getOne('RECORD_ID',
            expand: 'relField1,relField2.subRelField',
          );
          `
        )
      },
      tables: [
        idPathTable(tr("api_preview.param.id_view", "ID of the record to view.")),
        expandFieldsTable
      ],
      responses: [
        { status: "200", body: recordSample },
        apiError(403, "Only superusers can access this action."),
        apiError(404, "The requested resource wasn't found.")
      ]
    },
    {
      id: "realtime",
      nav: tr("api_preview.nav.realtime", "Realtime"),
      description: [
        tr("api_preview.realtime.desc", "Subscribe to realtime changes via Server-Sent Events (SSE)."),
        tr(
          "api_preview.realtime.events",
          "Events are sent for create, update and delete record operations."
        ),
        tr(
          "api_preview.realtime.single",
          "When you subscribe to a single record, the collection's View rule is used to determine whether the subscriber is allowed to receive the event message."
        ),
        tr(
          "api_preview.realtime.collection",
          "When you subscribe to an entire collection, the collection's List/Search rule is used instead."
        )
      ],
      method: "GET/POST",
      path: "/api/realtime",
      enabled: true,
      sdk: {
        js: sample(
          "js",
          `
          // (optionally) authenticate
          await pb.collection('users').authWithPassword('test@example.com', '1234567890');

          // subscribe to changes in any ${name} record
          pb.collection('${name}').subscribe('*', function (e) {
            console.log(e.action);
            console.log(e.record);
          }, { /* other options like: filter, expand, custom headers, etc. */ });

          // subscribe to changes only in the specified record
          pb.collection('${name}').subscribe('RECORD_ID', function (e) {
            console.log(e.action);
            console.log(e.record);
          });

          ...

          // unsubscribe - remove all 'RECORD_ID' subscriptions
          pb.collection('${name}').unsubscribe('RECORD_ID');

          // unsubscribe - remove all collection subscriptions
          pb.collection('${name}').unsubscribe();
          `
        ),
        dart: sample(
          "dart",
          `
          // (optionally) authenticate
          await pb.collection('users').authWithPassword('test@example.com', '1234567890');

          // subscribe to changes in any ${name} record
          pb.collection('${name}').subscribe('*', (e) {
            print(e.action);
            print(e.record);
          });

          // subscribe to changes only in the specified record
          pb.collection('${name}').subscribe('RECORD_ID', (e) {
            print(e.action);
            print(e.record);
          });

          ...

          // unsubscribe - remove all 'RECORD_ID' subscriptions
          pb.collection('${name}').unsubscribe('RECORD_ID');

          // unsubscribe - remove all collection subscriptions
          pb.collection('${name}').unsubscribe();
          `
        )
      },
      tables: [
        {
          heading: bodyParams,
          rows: [
            {
              name: "clientId",
              type: "String",
              requirement: "required",
              description: tr(
                "api_preview.param.client_id",
                'The SSE connection id received with the initial "PB_CONNECT" event.'
              )
            },
            {
              name: "subscriptions",
              type: "Array",
              requirement: "required",
              description: (
                <>
                  <p>
                    {tr(
                      "api_preview.param.subscriptions",
                      "The list of topics to subscribe to. Submitting an empty list removes all previous subscriptions."
                    )}
                  </p>
                  <pre className="apx-snippet">{`["${name}/*", "${name}/RECORD_ID"]`}</pre>
                </>
              )
            }
          ]
        }
      ],
      responses: [
        {
          status: "200",
          body: json({ action: "create", record: dummyRecord() }).replace(
            '"action": "create",',
            '"action": "create", // create, update or delete'
          )
        }
      ]
    }
  ];

  /* ------------------------------------------------------- mutable records */

  if (!isView) {
    endpoints.push(
      {
        id: "create",
        nav: tr("api_preview.nav.create", "Create"),
        description: [
          tr("api_preview.create.desc", "Creates a new record."),
          tr(
            "api_preview.body_content_types",
            "Body parameters could be sent as application/json or multipart/form-data."
          ),
          tr(
            "api_preview.file_upload",
            "File upload is supported only via multipart/form-data."
          )
        ],
        method: "POST",
        path: `${root}/records`,
        enabled: true,
        sdk: {
          js: sample(
            "js",
            code`
            // example create body
            const body = ${unwrapPlaceholders(json(submitPayload(false, true)))};

            const record = await pb.collection('${name}').create(body);${verificationHint}
            `
          ),
          dart: sample(
            "dart",
            code`
            // example create body
            final body = <String, dynamic>${json(submitPayload(false, false))};

            final record = await pb.collection('${name}').create(body: body, files: []);${verificationHint}
            `
          )
        },
        tables: [
          ...(isAuth ? [authBodyTable] : []),
          {
            heading: isAuth ? tr("api_preview.other_fields", "Other fields") : bodyParams,
            rows: bodyFieldRows(false)
          },
          expandFieldsTable
        ],
        responses: [
          { status: "200", body: recordSample },
          apiError(400, "Failed to create record.", requiredError(isAuth ? "email" : bodyFieldRows(false)[0]?.name ?? "someField")),
          apiError(403, "Only superusers can perform this action.")
        ]
      },
      {
        id: "update",
        nav: tr("api_preview.nav.update", "Update"),
        description: [
          tr("api_preview.update.desc", "Updates an existing record."),
          tr(
            "api_preview.body_content_types",
            "Body parameters could be sent as application/json or multipart/form-data."
          ),
          tr(
            "api_preview.update.password_note",
            "Note that in case of a password change all previously issued tokens for the record are automatically invalidated and you need to reauthenticate manually after the update call."
          )
        ],
        method: "PATCH",
        path: `${root}/records/:id`,
        enabled: true,
        sdk: {
          js: sample(
            "js",
            code`
            // example update body
            const body = ${unwrapPlaceholders(json(submitPayload(true, true)))};

            const record = await pb.collection('${name}').update('RECORD_ID', body);
            `
          ),
          dart: sample(
            "dart",
            code`
            // example update body
            final body = <String, dynamic>${json(submitPayload(true, false))};

            final record = await pb.collection('${name}').update(
              'RECORD_ID',
              body: body,
              files: [],
            );
            `
          )
        },
        tables: [
          idPathTable(tr("api_preview.param.id_update", "ID of the record to update.")),
          ...(isAuth ? [authBodyTable] : []),
          {
            heading: isAuth ? tr("api_preview.other_fields", "Other fields") : bodyParams,
            rows: bodyFieldRows(true)
          },
          expandFieldsTable
        ],
        responses: [
          { status: "200", body: recordSample },
          apiError(400, "Failed to update record.", requiredError(bodyFieldRows(true)[0]?.name ?? "someField")),
          apiError(403, "Only superusers can perform this action."),
          apiError(404, "The requested resource wasn't found.")
        ]
      },
      {
        id: "delete",
        nav: tr("api_preview.nav.delete", "Delete"),
        description: [tr("api_preview.delete.desc", "Deletes a single record.")],
        method: "DELETE",
        path: `${root}/records/:id`,
        enabled: true,
        sdk: {
          js: sample("js", `await pb.collection('${name}').delete('RECORD_ID');`),
          dart: sample("dart", `await pb.collection('${name}').delete('RECORD_ID');`)
        },
        tables: [idPathTable(tr("api_preview.param.id_delete", "ID of the record to delete."))],
        responses: [
          { status: "204", body: "null" },
          apiError(
            400,
            "Failed to delete record. Make sure that the record is not part of a required relation reference."
          ),
          apiError(403, "Only superusers can access this action."),
          apiError(404, "The requested resource wasn't found.")
        ]
      },
      {
        id: "batch",
        nav: tr("api_preview.nav.batch", "Batch"),
        description: [
          tr(
            "api_preview.batch.desc",
            "Batch and transactional create/update/upsert/delete of multiple records in a single request."
          ),
          tr(
            "api_preview.batch.enable",
            "The batch Web API needs to be explicitly enabled and configured from the app settings."
          ),
          tr(
            "api_preview.batch.perf",
            "Because this endpoint processes the requests in a single DB transaction it could degrade the performance of your application if not used with proper care and configuration."
          )
        ],
        method: "POST",
        path: "/api/batch",
        enabled: true,
        sdk: {
          js: sample(
            "js",
            `
            const batch = pb.createBatch();

            batch.collection('${name}').create({ ... });
            batch.collection('${name}').update('RECORD_ID', { ... });
            batch.collection('${name}').delete('RECORD_ID');
            batch.collection('${name}').upsert({ ... });

            const result = await batch.send();
            `
          ),
          dart: sample(
            "dart",
            `
            final batch = pb.createBatch();

            batch.collection('${name}').create(body: { ... });
            batch.collection('${name}').update('RECORD_ID', body: { ... });
            batch.collection('${name}').delete('RECORD_ID');
            batch.collection('${name}').upsert(body: { ... });

            final result = await batch.send();
            `
          )
        },
        tables: [
          {
            heading: bodyParams,
            rows: [
              {
                name: "requests",
                type: "Array",
                requirement: "required",
                description: (
                  <>
                    <p>
                      {tr(
                        "api_preview.param.requests",
                        "The list of the batch requests to process. When using the official SDKs the batch requests are transparently constructed by their service handler."
                      )}
                    </p>
                    <p>{tr("api_preview.param.requests_actions", "Supported batch actions:")}</p>
                    <pre className="apx-snippet">
                      {[
                        `POST   ${root}/records          // create`,
                        `PATCH  ${root}/records/:id      // update`,
                        `PUT    ${root}/records          // upsert (body must have an "id")`,
                        `DELETE ${root}/records/:id      // delete`
                      ].join("\n")}
                    </pre>
                    <p>
                      {tr(
                        "api_preview.param.requests_shape",
                        'Each request element accepts "url" (may include query parameters), "method", "headers" and "body".'
                      )}
                    </p>
                    <p>
                      {tr(
                        "api_preview.param.requests_multipart",
                        'When the batch request is sent as multipart/form-data, the batch action fields are expected as serialized json under the "@jsonPayload" field and file keys must follow the "requests.N.fileField" pattern.'
                      )}
                    </p>
                  </>
                )
              }
            ]
          }
        ],
        responses: [
          {
            status: "200",
            body: json([
              { status: 200, body: dummyRecord() },
              { status: 200, body: dummyRecord() }
            ])
          },
          {
            status: "400",
            body: json({
              status: 400,
              message: "Batch transaction failed.",
              data: {
                requests: {
                  "1": {
                    code: "batch_request_failed",
                    message: "Batch request failed.",
                    response: {
                      status: 400,
                      message: "Failed to create record.",
                      data: {
                        id: {
                          code: "validation_min_text_constraint",
                          message: "Must be at least 3 character(s).",
                          params: { min: 3 }
                        }
                      }
                    }
                  }
                }
              }
            })
          },
          apiError(403, "Batch requests are not allowed.")
        ]
      }
    );
  }

  /* ------------------------------------------------------------------ auth */

  if (isAuth) {
    const passwordEnabled = collection.passwordAuth?.enabled !== false;
    const oauth2Enabled = collection.oauth2?.enabled === true;
    const otpEnabled = collection.otp?.enabled === true;

    const authMethodsSample = json({
      mfa: { enabled: collection.mfa?.enabled === true, duration: collection.mfa?.enabled ? 100 : 0 },
      otp: { enabled: otpEnabled, duration: otpEnabled ? 180 : 0 },
      password: { enabled: passwordEnabled, identityFields: ["email"] },
      oauth2: {
        enabled: oauth2Enabled,
        providers: oauth2Enabled
          ? [
              {
                name: "github",
                displayName: "GitHub",
                state: "3Yd8jNkK...",
                authURL: "https://github.com/login/oauth/authorize?client_id=...&code_challenge=...",
                codeVerifier: "KxFDWz1B3...",
                codeChallenge: "d2ZoUE1RTE...",
                codeChallengeMethod: "S256"
              }
            ]
          : []
      }
    });

    endpoints.push(
      {
        id: "auth-methods",
        nav: tr("api_preview.nav.auth_methods", "List auth methods"),
        divider: true,
        description: [
          tr(
            "api_preview.auth_methods.desc",
            "Returns a public list with all allowed authentication methods for the collection."
          )
        ],
        method: "GET",
        path: `${root}/auth-methods`,
        enabled: true,
        sdk: {
          js: sample("js", `const result = await pb.collection('${name}').listAuthMethods();`),
          dart: sample("dart", `final result = await pb.collection('${name}').listAuthMethods();`)
        },
        tables: [{ heading: queryParams, rows: [fieldsRow] }],
        responses: [
          { status: "200", body: authMethodsSample },
          apiError(404, "Missing collection context.")
        ]
      },
      {
        id: "auth-with-password",
        nav: tr("api_preview.nav.auth_password", "Auth with password"),
        description: [
          tr(
            "api_preview.auth_with_password.desc",
            "Authenticate with a combination of identity (email/username) and password."
          )
        ],
        method: "POST",
        path: `${root}/auth-with-password`,
        enabled: passwordEnabled,
        sdk: {
          js: sample(
            "js",
            `
            const authData = await pb.collection('${name}').authWithPassword(
              'YOUR_EMAIL_OR_USERNAME',
              'YOUR_PASSWORD',
            );

            // after the above you can also access the auth data from the authStore
            console.log(pb.authStore.isValid);
            console.log(pb.authStore.token);
            console.log(pb.authStore.record.id);

            // "logout"
            pb.authStore.clear();
            `
          ),
          dart: sample(
            "dart",
            `
            final authData = await pb.collection('${name}').authWithPassword(
              'YOUR_EMAIL_OR_USERNAME',
              'YOUR_PASSWORD',
            );

            // after the above you can also access the auth data from the authStore
            print(pb.authStore.isValid);
            print(pb.authStore.token);
            print(pb.authStore.record.id);

            // "logout"
            pb.authStore.clear();
            `
          )
        },
        tables: [
          {
            heading: bodyParams,
            rows: [
              {
                name: "identity",
                type: "String",
                requirement: "required",
                description: tr(
                  "api_preview.param.identity",
                  "The identity value (email/username) of the record to authenticate."
                )
              },
              {
                name: "identityField",
                type: "String",
                requirement: "optional",
                description: tr(
                  "api_preview.param.identity_field",
                  "In case of multiple identity fields, explicitly set the field name to use when searching for the auth record. Leave it empty for auto detection."
                )
              },
              {
                name: "password",
                type: "String",
                requirement: "required",
                description: tr("api_preview.param.password", "The auth record password.")
              }
            ]
          },
          expandFieldsTable
        ],
        responses: [
          { status: "200", body: authSample },
          apiError(400, "Failed to authenticate.", requiredError("identity"))
        ]
      },
      {
        id: "auth-with-oauth2",
        nav: tr("api_preview.nav.auth_oauth2", "Auth with OAuth2"),
        description: [
          tr(
            "api_preview.auth_with_oauth2.desc",
            "Authenticate with an OAuth2 provider and returns a new auth token and record data."
          )
        ],
        method: "POST",
        path: `${root}/auth-with-oauth2`,
        enabled: oauth2Enabled,
        sdk: {
          js: sample(
            "js",
            `
            // OAuth2 authentication with a single realtime call.
            //
            // Make sure to register ${baseUrl}/api/oauth2-redirect
            // as redirect url in the OAuth2 app configuration.
            const authData = await pb.collection('${name}').authWithOAuth2({ provider: 'google' });

            // OR authenticate with manual OAuth2 code exchange
            // const authData = await pb.collection('${name}').authWithOAuth2Code(...);

            console.log(pb.authStore.isValid);
            console.log(pb.authStore.token);
            console.log(pb.authStore.record.id);
            `
          ),
          dart: sample(
            "dart",
            `
            // OAuth2 authentication with a single realtime call.
            //
            // Make sure to register ${baseUrl}/api/oauth2-redirect
            // as redirect url in the OAuth2 app configuration.
            final authData = await pb.collection('${name}').authWithOAuth2('google', (url) async {
              await launchUrl(url);
            });

            // OR authenticate with manual OAuth2 code exchange
            // final authData = await pb.collection('${name}').authWithOAuth2Code(...);

            print(pb.authStore.isValid);
            print(pb.authStore.token);
            print(pb.authStore.record.id);
            `
          )
        },
        tables: [
          {
            heading: bodyParams,
            rows: [
              {
                name: "provider",
                type: "String",
                requirement: "required",
                description: tr(
                  "api_preview.param.provider",
                  'The name of the OAuth2 client provider (eg. "google").'
                )
              },
              {
                name: "code",
                type: "String",
                requirement: "required",
                description: tr(
                  "api_preview.param.code",
                  "The authorization code returned from the initial request."
                )
              },
              {
                name: "codeVerifier",
                type: "String",
                requirement: "required",
                description: tr(
                  "api_preview.param.code_verifier",
                  "The code verifier sent with the initial request as part of the code_challenge."
                )
              },
              {
                name: "redirectURL",
                type: "String",
                requirement: "required",
                description: tr("api_preview.param.redirect_url", "The redirect url sent with the initial request.")
              },
              {
                name: "createData",
                type: "Object",
                requirement: "optional",
                description: tr(
                  "api_preview.param.create_data",
                  "Optional data that will be used when creating the auth record on OAuth2 sign-up. The created record must comply with the same requirements and validations as the regular create action and can only be in JSON (file uploads are not supported during OAuth2 sign-ups)."
                )
              }
            ]
          },
          expandFieldsTable
        ],
        responses: [
          {
            status: "200",
            body: json({
              token: "...JWT...",
              record: dummyRecord(),
              meta: {
                id: "abc123",
                name: "John Doe",
                username: "john.doe",
                email: "test@example.com",
                avatarURL: "https://example.com/avatar.png",
                accessToken: "...",
                refreshToken: "...",
                expiry: SAMPLE_DATE,
                isNew: false,
                rawUser: {}
              }
            })
          },
          apiError(400, "An error occurred while submitting the form.", requiredError("provider"))
        ]
      },
      // Official order after OAuth2: Auth with OTP → Auth refresh → Verification →
      // Password reset → Email change → Impersonate. Request/Confirm are sub-tabs.
      {
        id: "auth-with-otp",
        nav: tr("api_preview.nav.auth_otp", "Auth with OTP"),
        description: [],
        method: "POST",
        path: `${root}/request-otp`,
        enabled: otpEnabled,
        sdk: { js: "", dart: "" },
        tables: [],
        responses: [],
        variants: [
          {
            id: "request-otp",
            tab: tr("api_preview.nav.request_otp", "Request OTP"),
            description: [
              tr(
                "api_preview.request_otp.desc",
                "Sends a one-time password (OTP) email to the specified auth record."
              ),
              tr(
                "api_preview.request_otp.note",
                "An otpId is returned even if a user with the provided email doesn't exist as a very basic enumeration protection."
              )
            ],
            method: "POST",
            path: `${root}/request-otp`,
            sdk: {
              js: sample(
                "js",
                `
                // send an OTP email to the provided auth record
                const req = await pb.collection('${name}').requestOTP('test@example.com');

                // ... show a screen/popup to enter the password from the email ...
                console.log(req.otpId);
                `
              ),
              dart: sample(
                "dart",
                `
                // send an OTP email to the provided auth record
                final req = await pb.collection('${name}').requestOTP('test@example.com');

                // ... show a screen/popup to enter the password from the email ...
                print(req.otpId);
                `
              )
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "email",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.email_otp",
                      "The auth record email address to send the OTP request (if exists)."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "200", body: json({ otpId: "njvv1b1lkdbpp3m" }) },
              {
                status: "400",
                body: json({
                  status: 400,
                  message: "An error occurred while validating the submitted data.",
                  data: { email: { code: "validation_is_email", message: "Must be a valid email address." } }
                })
              },
              apiError(429, "You've sent too many OTP requests, please try again later.")
            ]
          },
          {
            id: "auth-with-otp-action",
            tab: tr("api_preview.nav.auth_otp", "Auth with OTP"),
            description: [
              tr("api_preview.auth_with_otp.desc", "Authenticate with a one-time/short-lived password (OTP)."),
              tr(
                "api_preview.auth_with_otp.note",
                "On successful authentication the user is also marked as verified (if the OTP source is email and the user is not verified already)."
              )
            ],
            method: "POST",
            path: `${root}/auth-with-otp`,
            sdk: {
              js: sample(
                "js",
                `
                // authenticate with the requested OTP id and the password from the email
                const authData = await pb.collection('${name}').authWithOTP(
                  'OTP_ID',
                  'YOUR_OTP',
                );

                console.log(pb.authStore.isValid);
                console.log(pb.authStore.token);
                console.log(pb.authStore.record.id);
                `
              ),
              dart: sample(
                "dart",
                `
                // authenticate with the requested OTP id and the password from the email
                final authData = await pb.collection('${name}').authWithOTP(
                  'OTP_ID',
                  'YOUR_OTP',
                );

                print(pb.authStore.isValid);
                print(pb.authStore.token);
                print(pb.authStore.record.id);
                `
              )
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "otpId",
                    type: "String",
                    requirement: "required",
                    description: tr("api_preview.param.otp_id", "The id of the OTP request.")
                  },
                  {
                    name: "password",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.otp_password",
                      "The one-time/short-lived password from the OTP request."
                    )
                  }
                ]
              },
              expandFieldsTable
            ],
            responses: [
              { status: "200", body: authSample },
              apiError(400, "Failed to authenticate.", requiredError("otpId"))
            ]
          }
        ]
      },
      {
        id: "auth-refresh",
        nav: tr("api_preview.nav.auth_refresh", "Auth refresh"),
        description: [
          tr(
            "api_preview.auth_refresh.desc",
            "Returns a new auth response (token and record data) for an already authenticated record."
          ),
          tr(
            "api_preview.auth_refresh.hint",
            "This method is usually called on page/screen reload to ensure that the previously stored auth data is still valid and up-to-date."
          )
        ],
        method: "POST",
        path: `${root}/auth-refresh`,
        note: authNote,
        enabled: true,
        sdk: {
          js: sample(
            "js",
            `
            const authData = await pb.collection('${name}').authRefresh();

            // after the above you can also access the refreshed auth data from the authStore
            console.log(pb.authStore.isValid);
            console.log(pb.authStore.token);
            console.log(pb.authStore.record.id);
            `
          ),
          dart: sample(
            "dart",
            `
            final authData = await pb.collection('${name}').authRefresh();

            // after the above you can also access the refreshed auth data from the authStore
            print(pb.authStore.isValid);
            print(pb.authStore.token);
            print(pb.authStore.record.id);
            `
          )
        },
        tables: [expandFieldsTable],
        responses: [
          { status: "200", body: authSample },
          apiError(401, "The request requires valid record authorization token to be set."),
          apiError(403, "The authorized record model is not allowed to perform this action."),
          apiError(404, "Missing auth record context.")
        ]
      },
      {
        id: "verification",
        nav: tr("api_preview.nav.verification", "Verification"),
        description: [],
        method: "POST",
        path: `${root}/request-verification`,
        enabled: true,
        sdk: { js: "", dart: "" },
        tables: [],
        responses: [],
        variants: [
          {
            id: "request-verification",
            tab: tr("api_preview.nav.request_verification", "Request verification"),
            description: [
              tr(
                "api_preview.request_verification.desc",
                "Sends an account verification email request to the specified auth record."
              )
            ],
            method: "POST",
            path: `${root}/request-verification`,
            sdk: {
              js: sample("js", `await pb.collection('${name}').requestVerification('test@example.com');`),
              dart: sample("dart", `await pb.collection('${name}').requestVerification('test@example.com');`)
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "email",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.email_verification",
                      "The auth record email address to send the verification request (if exists)."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("email"))
            ]
          },
          {
            id: "confirm-verification",
            tab: tr("api_preview.nav.confirm_verification", "Confirm verification"),
            description: [
              tr(
                "api_preview.confirm_verification.desc",
                "Confirms the account verification request with the token from the verification email."
              )
            ],
            method: "POST",
            path: `${root}/confirm-verification`,
            sdk: {
              js: sample("js", `await pb.collection('${name}').confirmVerification('VERIFICATION_TOKEN');`),
              dart: sample("dart", `await pb.collection('${name}').confirmVerification('VERIFICATION_TOKEN');`)
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "token",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.token_verification",
                      "The token from the verification request email."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("token"))
            ]
          }
        ]
      },
      {
        id: "password-reset",
        nav: tr("api_preview.nav.password_reset", "Password reset"),
        description: [],
        method: "POST",
        path: `${root}/request-password-reset`,
        enabled: passwordEnabled,
        sdk: { js: "", dart: "" },
        tables: [],
        responses: [],
        variants: [
          {
            id: "request-password-reset",
            tab: tr("api_preview.nav.request_password_reset", "Request password reset"),
            description: [
              tr(
                "api_preview.request_password_reset.desc",
                "Sends a password reset email request to the specified auth record."
              )
            ],
            method: "POST",
            path: `${root}/request-password-reset`,
            sdk: {
              js: sample("js", `await pb.collection('${name}').requestPasswordReset('test@example.com');`),
              dart: sample("dart", `await pb.collection('${name}').requestPasswordReset('test@example.com');`)
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "email",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.email_password_reset",
                      "The auth record email address to send the password reset request (if exists)."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("email"))
            ]
          },
          {
            id: "confirm-password-reset",
            tab: tr("api_preview.nav.confirm_password_reset", "Confirm password reset"),
            description: [
              tr(
                "api_preview.confirm_password_reset.desc",
                "Confirms the password reset request and sets a new password."
              ),
              tr(
                "api_preview.confirm_password_reset.note",
                "On successful password reset all previously issued tokens for the record are invalidated and the record is marked as verified."
              )
            ],
            method: "POST",
            path: `${root}/confirm-password-reset`,
            sdk: {
              js: sample(
                "js",
                `
                await pb.collection('${name}').confirmPasswordReset(
                  'RESET_TOKEN',
                  'NEW_PASSWORD',
                  'NEW_PASSWORD_CONFIRM',
                );
                `
              ),
              dart: sample(
                "dart",
                `
                await pb.collection('${name}').confirmPasswordReset(
                  'RESET_TOKEN',
                  'NEW_PASSWORD',
                  'NEW_PASSWORD_CONFIRM',
                );
                `
              )
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "token",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.token_password_reset",
                      "The token from the password reset request email."
                    )
                  },
                  {
                    name: "password",
                    type: "String",
                    requirement: "required",
                    description: tr("api_preview.param.new_password", "The new password to set.")
                  },
                  {
                    name: "passwordConfirm",
                    type: "String",
                    requirement: "required",
                    description: tr("api_preview.param.new_password_confirm", "Confirmation of the new password.")
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("token"))
            ]
          }
        ]
      },
      {
        id: "email-change",
        nav: tr("api_preview.nav.email_change", "Email change"),
        description: [],
        method: "POST",
        path: `${root}/request-email-change`,
        enabled: true,
        sdk: { js: "", dart: "" },
        tables: [],
        responses: [],
        variants: [
          {
            id: "request-email-change",
            tab: tr("api_preview.nav.request_email_change", "Request email change"),
            description: [
              tr(
                "api_preview.request_email_change.desc",
                "Sends an email change request to the currently authenticated auth record."
              ),
              tr(
                "api_preview.confirm_email_change.note",
                "On successful email change all previously issued tokens for the record are invalidated."
              )
            ],
            method: "POST",
            path: `${root}/request-email-change`,
            note: authNote,
            sdk: {
              js: sample("js", `await pb.collection('${name}').requestEmailChange('new@example.com');`),
              dart: sample("dart", `await pb.collection('${name}').requestEmailChange('new@example.com');`)
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "newEmail",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.new_email",
                      "The new email address to send the change email request."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("newEmail")),
              apiError(401, "The request requires valid record authorization token to be set."),
              apiError(403, "The authorized record model is not allowed to perform this action.")
            ]
          },
          {
            id: "confirm-email-change",
            tab: tr("api_preview.nav.confirm_email_change", "Confirm email change"),
            description: [
              tr(
                "api_preview.confirm_email_change.desc",
                "Confirms the email change request with the token from the confirmation email."
              ),
              tr(
                "api_preview.confirm_email_change.note",
                "On successful email change all previously issued tokens for the record are invalidated."
              )
            ],
            method: "POST",
            path: `${root}/confirm-email-change`,
            sdk: {
              js: sample(
                "js",
                `
                await pb.collection('${name}').confirmEmailChange(
                  'EMAIL_CHANGE_TOKEN',
                  'YOUR_PASSWORD',
                );
                `
              ),
              dart: sample(
                "dart",
                `
                await pb.collection('${name}').confirmEmailChange(
                  'EMAIL_CHANGE_TOKEN',
                  'YOUR_PASSWORD',
                );
                `
              )
            },
            tables: [
              {
                heading: bodyParams,
                rows: [
                  {
                    name: "token",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.token_email_change",
                      "The token from the change email request email."
                    )
                  },
                  {
                    name: "password",
                    type: "String",
                    requirement: "required",
                    description: tr(
                      "api_preview.param.password_email_change",
                      "The account password to confirm the email change."
                    )
                  }
                ]
              }
            ],
            responses: [
              { status: "204", body: "null" },
              apiError(400, "An error occurred while validating the submitted data.", requiredError("token"))
            ]
          }
        ]
      },
      {
        id: "impersonate",
        nav: tr("api_preview.nav.impersonate", "Impersonate"),
        description: [
          tr(
            "api_preview.impersonate.desc",
            "Returns a new auth token for the specified auth record without knowing its password."
          ),
          tr(
            "api_preview.impersonate.note",
            "Only superusers can perform this action. The generated token is a static one that cannot be refreshed."
          )
        ],
        method: "POST",
        path: `${root}/impersonate/:id`,
        note: superuserNote,
        enabled: true,
        sdk: {
          js: sample(
            "js",
            `
            await pb.collection('_superusers').authWithPassword('test@example.com', '1234567890');

            // returns a new isolated client authenticated as the impersonated record
            // (the optional second argument is the token duration in seconds)
            const impersonateClient = await pb.collection('${name}').impersonate('RECORD_ID', 3600);

            console.log(impersonateClient.authStore.token);
            console.log(impersonateClient.authStore.record.id);
            `
          ),
          dart: sample(
            "dart",
            `
            await pb.collection('_superusers').authWithPassword('test@example.com', '1234567890');

            // returns a new isolated client authenticated as the impersonated record
            // (the optional second argument is the token duration in seconds)
            final impersonateClient = await pb.collection('${name}').impersonate('RECORD_ID', 3600);

            print(impersonateClient.authStore.token);
            print(impersonateClient.authStore.record.id);
            `
          )
        },
        tables: [
          idPathTable(tr("api_preview.param.id_impersonate", "ID of the auth record to impersonate.")),
          {
            heading: bodyParams,
            rows: [
              {
                name: "duration",
                type: "Number",
                requirement: "optional",
                description: tr(
                  "api_preview.param.duration",
                  "Optional custom token duration in seconds. Defaults to the collection auth token duration."
                )
              }
            ]
          },
          expandFieldsTable
        ],
        responses: [
          { status: "200", body: authSample },
          apiError(400, "An error occurred while validating the submitted data.", {
            duration: { code: "validation_min_greater_equal_than_required", message: "Must be no less than 0." }
          }),
          apiError(403, "The authorized record model is not allowed to perform this action."),
          apiError(404, "The requested resource wasn't found.")
        ]
      }
    );
  }

  // Official menu order + attach curl samples for every endpoint.
  return finalizeEndpoints(endpoints, baseUrl, name, {
    create: JSON.stringify(submitPayload(false, false)),
    update: JSON.stringify(submitPayload(true, false))
  });
}

/* -------------------------------------------------------------------------- */
/* building blocks                                                             */
/* -------------------------------------------------------------------------- */

function useSdkPreference(): [Sdk, (next: Sdk) => void] {
  const [sdk, setSdk] = useState<Sdk>(readStoredSdk);

  useEffect(() => {
    const sync = () => setSdk(readStoredSdk());
    window.addEventListener("storage", sync);
    window.addEventListener(SDK_CHANGE_EVENT, sync);
    return () => {
      window.removeEventListener("storage", sync);
      window.removeEventListener(SDK_CHANGE_EVENT, sync);
    };
  }, []);

  const select = useCallback((next: Sdk) => {
    setSdk(next);
    try {
      window.localStorage.setItem(SDK_STORAGE_KEY, next);
    } catch {
      // storage may be unavailable (private mode) - the in-memory value still applies
    }
    window.dispatchEvent(new Event(SDK_CHANGE_EVENT));
  }, []);

  return [sdk, select];
}

function readStoredSdk(): Sdk {
  try {
    const value = window.localStorage.getItem(SDK_STORAGE_KEY);
    if (value === "dart" || value === "curl" || value === "js") return value;
    return "js";
  } catch {
    return "js";
  }
}

function CodeBlock({ value }: { value: string }): React.JSX.Element {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = useCallback(() => {
    const write = navigator.clipboard?.writeText(value);
    if (write) {
      write.then(() => setCopied(true)).catch(() => undefined);
    }
  }, [value]);

  return (
    <div className="apx-code">
      <button
        type="button"
        className="apx-code-copy"
        onClick={copy}
        title={copied ? t("actions.copied", "Copied") : t("actions.copy", "Copy")}
        aria-label={copied ? t("actions.copied", "Copied") : t("actions.copy", "Copy")}
      >
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </button>
      <pre>
        <code>{value}</code>
      </pre>
    </div>
  );
}

function ParamTableView({ table }: { table: ParamTable }): React.JSX.Element {
  const { t } = useTranslation();

  return (
    <div className="apx-table-wrap">
      <table className="apx-table">
        <thead>
          <tr>
            <th className="apx-col-name">{table.heading}</th>
            <th className="apx-col-type">{t("api_preview.type", "Type")}</th>
            <th>{t("api_preview.description", "Description")}</th>
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row) => (
            <tr key={row.name}>
              <td className="apx-col-name">
                <span className="apx-param">{row.name}</span>
                {row.requirement === "required" && (
                  <em className="apx-required">{t("api_preview.required", "(required)")}</em>
                )}
                {row.requirement === "optional" && (
                  <em className="apx-optional">{t("api_preview.optional", "(optional)")}</em>
                )}
              </td>
              <td className="apx-col-type">
                <span className="apx-type">{row.type}</span>
              </td>
              <td className="apx-col-desc">{row.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResponseSamples({ responses }: { responses: ResponseSample[] }): React.JSX.Element | null {
  const [index, setIndex] = useState(0);
  const active = responses[index] ?? responses[0];

  if (!active) return null;

  return (
    <div className="apx-responses">
      <div className="apx-tabs" role="tablist">
        {responses.map((response, i) => (
          <button
            key={response.status}
            type="button"
            role="tab"
            aria-selected={i === index}
            className={`apx-tab apx-status-${response.status.charAt(0)}${i === index ? " active" : ""}`}
            onClick={() => setIndex(i)}
          >
            {response.status}
          </button>
        ))}
      </div>
      <CodeBlock value={active.body} />
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* main component                                                              */
/* -------------------------------------------------------------------------- */

export function ApiPreview({ collection, baseUrl, onClose }: ApiPreviewProps): React.JSX.Element {
  const { t } = useTranslation();
  const [activeId, setActiveId] = useState("list");
  const [variantId, setVariantId] = useState<string | null>(null);
  const [sdk, setSdk] = useSdkPreference();
  const bodyRef = useRef<HTMLDivElement>(null);
  const { exiting, requestClose, onPanelAnimationEnd } = useDrawerTransition(onClose);
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(requestClose, {
    active: !exiting
  });

  const tr = useCallback<Translate>((key, fallback) => t(key, fallback), [t]);

  const endpoints = useMemo(
    () => buildEndpoints(collection, baseUrl, tr),
    [collection, baseUrl, tr]
  );

  const active = useMemo(
    () => endpoints.find((endpoint) => endpoint.id === activeId) ?? endpoints[0],
    [endpoints, activeId]
  );

  const activeVariant = useMemo(() => {
    if (!active?.variants?.length) return null;
    return active.variants.find((variant) => variant.id === variantId) ?? active.variants[0];
  }, [active, variantId]);

  /** Resolved content for the current nav item (+ optional request/confirm sub-tab). */
  const view = useMemo(() => {
    if (!active) return null;
    if (!activeVariant) return active;
    return {
      ...active,
      description: activeVariant.description,
      method: activeVariant.method,
      path: activeVariant.path,
      note: activeVariant.note,
      sdk: activeVariant.sdk,
      tables: activeVariant.tables,
      responses: activeVariant.responses
    };
  }, [active, activeVariant]);

  // reset the selection whenever the previewed collection changes
  useEffect(() => {
    setActiveId("list");
    setVariantId(null);
  }, [collection.id]);

  // pick the first sub-action when switching grouped auth nav items
  useEffect(() => {
    setVariantId(active?.variants?.[0]?.id ?? null);
  }, [active?.id, active?.variants]);

  // always start a newly selected endpoint from the top
  useEffect(() => {
    bodyRef.current?.scrollTo({ top: 0 });
  }, [active?.id, activeVariant?.id]);

  const disabledHint = t("api_preview.not_enabled", "Not enabled for the collection");

  return (
    <div
      className={`apx-backdrop${exiting ? " is-exiting" : ""}`}
      role="presentation"
      onMouseDown={exiting ? undefined : onBackdropMouseDown}
      onMouseUp={exiting ? undefined : onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className={`apx-dialog${exiting ? " is-exiting" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={t("api_preview.title", "API Preview")}
        tabIndex={-1}
        onAnimationEnd={onPanelAnimationEnd}
      >
        <aside className="apx-sidebar">
          <div className="apx-brand">
            <span className="apx-brand-title">{t("api_preview.title", "API Preview")}</span>
            <span className="apx-brand-collection">{collection.name}</span>
          </div>
          <nav className="apx-nav">
            {endpoints.map((endpoint) => (
              <Fragment key={endpoint.id}>
                {endpoint.divider && <hr className="apx-nav-divider" />}
                <button
                  type="button"
                  className={`apx-nav-item${endpoint.id === active?.id ? " is-active" : ""}`}
                  aria-disabled={!endpoint.enabled}
                  aria-current={endpoint.id === active?.id}
                  title={endpoint.enabled ? undefined : disabledHint}
                  onClick={() => {
                    if (endpoint.enabled) setActiveId(endpoint.id);
                  }}
                >
                  {endpoint.nav}
                </button>
              </Fragment>
            ))}
          </nav>
        </aside>

        <div className="apx-main">
          <header className="apx-header">
            <h3 className="apx-heading">
              {active?.nav}
              <span className="apx-heading-collection">({collection.name})</span>
            </h3>
            <button
              type="button"
              className="apx-close"
              onClick={requestClose}
              disabled={exiting}
              title={t("actions.close", "Close")}
              aria-label={t("actions.close", "Close")}
            >
              <X size={16} />
            </button>
          </header>

          <div className="apx-body" ref={bodyRef}>
            {view && active && (
              <>
                {view.description.map((paragraph, index) => (
                  <p key={index} className="apx-desc">
                    {paragraph}
                  </p>
                ))}

                <div className="apx-sdk">
                  <div className="apx-tabs" role="tablist">
                    {SDK_TABS.map((tab) => (
                      <button
                        key={tab.id}
                        type="button"
                        role="tab"
                        aria-selected={tab.id === sdk}
                        className={`apx-tab${tab.id === sdk ? " active" : ""}`}
                        onClick={() => setSdk(tab.id)}
                      >
                        {tab.label}
                      </button>
                    ))}
                  </div>
                  <CodeBlock value={view.sdk[sdk]} />
                </div>

                {active.variants && active.variants.length > 1 && (
                  <div className="apx-action-tabs" role="tablist" aria-label={active.nav}>
                    {active.variants.map((variant) => (
                      <button
                        key={variant.id}
                        type="button"
                        role="tab"
                        aria-selected={variant.id === (activeVariant?.id ?? active.variants![0].id)}
                        className={`apx-action-tab${
                          variant.id === (activeVariant?.id ?? active.variants![0].id) ? " is-active" : ""
                        }`}
                        onClick={() => setVariantId(variant.id)}
                      >
                        {variant.tab}
                      </button>
                    ))}
                  </div>
                )}

                <h4 className="apx-section">{t("api_preview.api_details", "API details")}</h4>
                <div className={`apx-endpoint apx-method-${view.method.toLowerCase().replace("/", "-")}`}>
                  <span className="apx-method">{view.method}</span>
                  <span className="apx-path">{view.path}</span>
                  {view.note && <span className="apx-endpoint-note">{view.note}</span>}
                </div>

                {view.tables
                  .filter((table) => table.rows.length > 0)
                  .map((table) => (
                    <ParamTableView
                      key={`${active.id}-${activeVariant?.id ?? "root"}-${table.heading}`}
                      table={table}
                    />
                  ))}

                <h4 className="apx-section">{t("api_preview.example_responses", "Example responses")}</h4>
                <ResponseSamples
                  key={`${active.id}-${activeVariant?.id ?? "root"}`}
                  responses={view.responses}
                />
              </>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}

export default ApiPreview;

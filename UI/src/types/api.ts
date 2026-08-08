/**
 * Shared domain types — the single source of truth for PocketBase schema models
 * used across App.tsx and all component files.
 *
 * Extracted from duplicate definitions in App.tsx, FieldEditor.tsx,
 * RecordFieldControl.tsx, and FileFieldControl.tsx.
 */

/** A single field definition in a collection schema (PocketBase v0.23+ flattened). */
export type FieldSchema = {
  id?: string;
  name: string;
  type: string;
  required?: boolean;
  /** @deprecated removed in PocketBase v0.23 — uniqueness is expressed through indexes. */
  unique?: boolean;
  hidden?: boolean;
  system?: boolean;
  presentable?: boolean;
  collectionId?: string;
  collectionIds?: string[];
  minSelect?: number;
  maxSelect?: number;
  maxFiles?: number;
  maxSize?: number;
  mimeTypes?: string[];
  thumbs?: string[];
  protected?: boolean;
  options?: Record<string, unknown>;
  // Per-type schema options (flattened field schema, PocketBase v0.23+).
  min?: number | string;
  max?: number | string;
  pattern?: string;
  autogeneratePattern?: string;
  onlyInt?: boolean;
  onlyDomains?: string[];
  exceptDomains?: string[];
  onCreate?: boolean;
  onUpdate?: boolean;
  values?: string[];
  cascadeDelete?: boolean;
  convertURLs?: boolean;
  cost?: number;
};

/** A minimal subset of FieldSchema used by file-field controls. */
export type FileField = Pick<
  FieldSchema,
  "name" | "maxFiles" | "maxSelect" | "mimeTypes" | "thumbs" | "protected" | "options"
>;

/** A collection definition in the schema. */
export type CollectionSchema = {
  id: string;
  name: string;
  type: "base" | "auth" | "view";
  system?: boolean;
  fields?: FieldSchema[];
  listRule?: string | null;
  viewRule?: string | null;
  createRule?: string | null;
  updateRule?: string | null;
  deleteRule?: string | null;
  /** auth-only */
  authRule?: string | null;
  manageRule?: string | null;
  passwordAuth?: { enabled?: boolean; identityFields?: string[] };
  otp?: { enabled?: boolean; duration?: number; length?: number; emailTemplate?: EmailTemplate };
  mfa?: { enabled?: boolean; duration?: number; rule?: string | null };
  oauth2?: {
    enabled?: boolean;
    providers?: OAuth2ProviderConfig[];
    mappedFields?: Record<string, string>;
  };
  authToken?: TokenConfig;
  passwordResetToken?: TokenConfig;
  verificationToken?: TokenConfig;
  emailChangeToken?: TokenConfig;
  fileToken?: TokenConfig;
  authAlert?: { enabled?: boolean; emailTemplate?: EmailTemplate };
  verificationTemplate?: EmailTemplate;
  resetPasswordTemplate?: EmailTemplate;
  confirmEmailChangeTemplate?: EmailTemplate;
  viewQuery?: string;
  indexes?: string[];
  created?: string;
  updated?: string;
  [key: string]: unknown;
};

export type EmailTemplate = {
  subject?: string;
  body?: string;
};

export type TokenConfig = {
  duration?: number;
  secret?: string;
};

export type OAuth2ProviderConfig = {
  name: string;
  clientId?: string;
  clientSecret?: string;
  authURL?: string;
  tokenURL?: string;
  userInfoURL?: string;
  displayName?: string;
  scopes?: string[];
  pkce?: boolean;
  extra?: Record<string, unknown>;
};

/** A collection option used in dropdowns and pickers. */
export type CollectionOption = {
  id: string;
  name: string;
  type: string;
};

/** A single data record (untyped bag of field values + system fields). */
export type RecordItem = Record<string, unknown> & {
  id?: string;
  created?: string;
  updated?: string;
};

/** Generic list response matching PocketBase's paginated JSON shape. */
export type ListResponse<T> = {
  page: number;
  perPage: number;
  totalItems: number;
  totalPages: number;
  items: T[];
};

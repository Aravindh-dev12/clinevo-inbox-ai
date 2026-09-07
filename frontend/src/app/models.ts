export interface InboxMessage {
  id: number;
  internetMessageId?: string;
  sender: string;
  subject?: string;
  receivedAt: string;
  bodyText?: string;
  status: string;
  aiSummary?: string;
  processingMs?: number;
}

export interface Classification {
  category: 'ICSR' | 'PQC' | 'MI' | 'NOT_RELEVANT';
  confidence: number;
  reason: string;
}

export interface Attachment {
  id: number;
  fileName: string;
  mimeType?: string;
  pdfType?: string;
  detectedLanguage?: string;
  ocrConfidence?: number;
  processingMs?: number;
  processingStatus: string;
}

export interface Fact {
  id: number;
  factGroup: string;
  fieldName: string;
  fieldValue?: string;
  confidence: number;
  sourceType: string;
  sourceName: string;
  sourcePage?: number;
  evidenceText?: string;
}

export interface ReviewAction {
  actionType: string;
  reviewer: string;
  fieldName?: string;
  previousValue?: string;
  newValue?: string;
  note?: string;
  createdAt: string;
}

export interface InboxDetail {
  message: InboxMessage;
  classifications: Classification[];
  attachments: Attachment[];
  facts: Fact[];
  reviewActions: ReviewAction[];
}

export interface FactOverride {
  factId: number;
  newValue: string;
}

export interface ReviewRequest {
  action: 'ACCEPT' | 'OVERRIDE';
  overrideCategory?: string;
  note?: string;
  reviewer: string;
  factOverrides: FactOverride[];
}

export interface LiteratureSource {
  source_type: 'EMAIL' | 'PDF';
  source_name: string;
  page?: number;
  evidence?: string;
}

export interface LiteratureExtractedValue {
  value: string;
  confidence: number;
  source?: LiteratureSource;
}

export interface LiteratureCaseResult {
  case_id: string;
  reportable: boolean;
  confidence: number;
  relevance_reason: string;
  summary: string;
  classifications: Classification[];
  extracted_facts: Record<string, Record<string, LiteratureExtractedValue>>;
  source_pages: number[];
}

export interface LiteratureDocumentResult {
  file_name: string;
  pdf_type: string;
  detected_language: string;
  cases: LiteratureCaseResult[];
  case_count: number;
  processing_ms: number;
}

export interface LiteratureBatchResult {
  documents: LiteratureDocumentResult[];
  total_documents: number;
  total_cases: number;
  processing_ms: number;
}

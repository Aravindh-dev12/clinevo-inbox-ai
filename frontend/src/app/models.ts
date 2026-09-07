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

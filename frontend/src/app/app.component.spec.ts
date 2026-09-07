import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient()]
    }).compileComponents();
  });

  it('renders the review workspace title', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toContain('Smart Inbox Review');
  });

  it('formats confidence conservatively', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance.confidence(0.946)).toBe('95%');
    expect(fixture.componentInstance.confidence(undefined)).toBe('—');
  });
});

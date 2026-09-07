#!/usr/bin/env python3
"""Generate the deterministic synthetic PDF corpus. Never use real patient/client data."""
from __future__ import annotations
import json, random, textwrap
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle
ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT/'generated'; PDFS=OUT/'pdfs'; EMAILS=OUT/'emails'; PDFS.mkdir(parents=True,exist_ok=True); EMAILS.mkdir(parents=True,exist_ok=True); styles=getSampleStyleSheet()
DIGITAL=[
('digital_icsr_form_01.pdf','Synthetic Adverse Event Report',[('Patient','Patient P-001 is a 45-year-old female.'),('Reporter','Reported by Dr. Maya Rao, treating physician, India.'),('Product','Clinevex 10 mg tablet, oral, once daily. Started 02-Aug-2026.'),('Reaction','On 05-Aug-2026 the patient developed generalized rash and itching. Drug was stopped; symptoms resolved within two days.'),('Seriousness','No hospitalization, no life-threatening event, no death.'),('Narrative','Synthetic training data only; all names and facts are fictional.')],[['Date','ALT (U/L)','AST (U/L)'],['05-Aug-2026','28','24'],['07-Aug-2026','26','22']]),
('digital_icsr_form_02.pdf','Synthetic Safety Intake Form',[('Patient','Patient P-002 is a 67-year-old male, weight 78 kg.'),('Reporter','Spouse reported the event by email from the United Kingdom.'),('Product','Novera 20 mg, oral, twice daily, started 12-Jul-2026.'),('Reaction','Severe dizziness began 13-Jul-2026 and the patient fell at home.'),('Outcome','Observed overnight in hospital and discharged the next morning; recovered.')],[['Dose time','Dose'],['08:00','20 mg'],['20:00','20 mg']]),
('digital_pqc_01.pdf','Synthetic Product Quality Complaint',[('Product','Dermacline topical gel, batch LOT-A17.'),('Complaint','Customer reports the tube seal was broken before first use and the gel appeared darker than expected.'),('Photo','A product photo was mentioned as attached in the original complaint.'),('Patient safety','No patient used the product and no adverse reaction was reported.')],None),
('digital_mi_01.pdf','Synthetic Medical Information Request',[('Requester','Hospital pharmacist.'),('Product','Clinevex 10 mg tablets.'),('Question','Can Clinevex be taken with food, and is dose adjustment required in mild renal impairment?'),('Safety','No adverse event or product defect was reported.')],None),
('digital_combo_01.pdf','Synthetic Combined Safety and Quality Report',[('Patient','Patient P-005 is a 32-year-old male.'),('Reporter','Reported by the patient in Canada.'),('Product','Novera prefilled syringe, batch B-2208.'),('Complaint','The syringe plunger was cracked and leaked during administration.'),('Reaction','Within minutes the patient developed localized redness and swelling at the injection site.'),('Outcome','Symptoms improved without treatment after four hours.')],None)]
ARTICLES=[
('article_case_01.pdf','Case Report: Rash Following Clinevex','A 51-year-old woman (Patient A1) received Clinevex 10 mg orally once daily. Dr. Elena Park reported that three days after treatment began, the patient developed a widespread itchy rash. Clinevex was discontinued and the rash resolved over 48 hours. The event did not require hospitalization.','General background on skin reactions and fictional literature context. This is not an additional patient case.'),
('article_case_02.pdf','Case Report: Syncope During Novera Therapy','A 73-year-old man (Patient A2) started Novera 20 mg twice daily. On day two he experienced dizziness followed by syncope. He was admitted for observation and recovered. The treating physician in Ireland submitted the report.','This fictional discussion reviews syncope broadly and contains no other identifiable patient.'),
('article_case_03.pdf','Case Report: Device Defect and Injection-Site Reaction','A 40-year-old woman (Patient A3) used a Novera autoinjector from lot AUTO-44. The needle shield was visibly damaged. After administration she experienced injection-site pain and swelling lasting six hours. A pharmacist in Singapore reported the case.','Device quality defects and local reactions are discussed generally. Only the case paragraph is patient-specific.'),
('article_case_04.pdf','Two Fictional Cases of Clinevex Intolerance','Case 1: Patient A4, a 22-year-old man, developed nausea two hours after the first Clinevex dose; symptoms resolved after stopping. Case 2: Patient A5, a 60-year-old woman, developed severe diarrhea after three days and required overnight hospitalization. Both were reported by clinicians in Spain.','The article compares the two fictional cases. No real patient data are used.'),
('article_case_05.pdf','Case Report: Non-serious Headache','A 35-year-old woman (Patient A6) took Clinevex 5 mg daily. She developed headache on the second treatment day. The patient self-reported the event in New Zealand. Treatment continued and the headache resolved without intervention.','The discussion contains only general fictional tolerability information.')]
NON_ENGLISH=[('non_english_es_01.pdf','Informe de seguridad sintético',[('Paciente','Paciente P-ES1, mujer de 44 años.'),('Reportante','Médico tratante en España.'),('Producto','Clinevex 10 mg por vía oral una vez al día.'),('Reacción','Dos días después de iniciar el tratamiento presentó erupción cutánea y picor.'),('Resultado','El medicamento se suspendió y la paciente se recuperó en 48 horas.'),('Aviso','Todos los datos son ficticios y se usan únicamente para pruebas.')]),('non_english_fr_01.pdf','Demande d’information médicale synthétique',[('Demandeur','Pharmacien hospitalier en France.'),('Produit','Novera 20 mg comprimé.'),('Question','Le médicament peut-il être pris avec des aliments et existe-t-il une interaction avec un antiacide?'),('Sécurité','Aucun effet indésirable et aucun défaut du produit n’ont été signalés.'),('Avis','Toutes les informations sont fictives.')])]
SCANNED=[('scanned_icsr_01.pdf',11,['HAND-FILLED MOCK SAFETY FORM - SYNTHETIC','Patient: P-006, age 28, female','Reporter: nurse, Australia','Drug: Clinevex 5 mg oral daily','Started: 18-Aug-2026','Event: vomiting and abdominal pain on 19-Aug-2026','Outcome: recovered same day','Serious? No']),('scanned_pqc_01.pdf',19,['SCANNED QUALITY COMPLAINT - SYNTHETIC','Product: Novera pen','Lot: PEN-771','Problem: cap was cracked and device would not click','Photo: customer said a photo is available','No dose administered','No adverse event reported'])]
def digital(path,title,sections,table=None):
    doc=SimpleDocTemplate(str(path),pagesize=A4,rightMargin=18*mm,leftMargin=18*mm,topMargin=18*mm,bottomMargin=18*mm); story=[Paragraph(title,styles['Heading1']),Spacer(1,8)]
    for heading,text in sections: story += [Paragraph(heading,styles['Heading2']),Paragraph(text,styles['BodyText']),Spacer(1,8)]
    if table:
        t=Table(table,repeatRows=1); t.setStyle(TableStyle([('GRID',(0,0),(-1,-1),.5,colors.grey),('BACKGROUND',(0,0),(-1,0),colors.lightgrey),('FONTNAME',(0,0),(-1,0),'Helvetica-Bold'),('PADDING',(0,0),(-1,-1),5)])); story.append(t)
    doc.build(story)
def article(path,title,case_text,discussion):
    c=canvas.Canvas(str(path),pagesize=A4); width,height=A4; c.setFont('Helvetica-Bold',16); c.drawString(18*mm,height-20*mm,title); c.setFont('Helvetica',9); c.drawString(18*mm,height-27*mm,'Fictional journal article created solely for software testing'); col=(width-42*mm)/2
    for x,heading,text in [(18*mm,'Case report',case_text),(24*mm+col,'Discussion',discussion)]:
        y=height-38*mm; c.setFont('Helvetica-Bold',11); c.drawString(x,y,heading); obj=c.beginText(x,y-7*mm); obj.setFont('Helvetica',9); obj.setLeading(12)
        for line in textwrap.wrap(text,52): obj.textLine(line)
        c.drawText(obj)
    c.setFont('Helvetica-Bold',10); c.drawString(18*mm,35*mm,'References'); c.setFont('Helvetica',8); c.drawString(18*mm,30*mm,'1. Fictional Reference A. 2. Fictional Reference B.'); c.save()
def scanned(path,seed,lines):
    random.seed(seed); w,h=1700,2200; img=Image.new('RGB',(w,h),(244,241,232)); draw=ImageDraw.Draw(img)
    for y in range(180,1950,110): draw.line((120,y,w-120,y),fill=(180,180,175),width=2)
    draw.rectangle((95,90,w-95,h-90),outline=(140,140,135),width=4); font=ImageFont.load_default(); y=125
    for line in lines: draw.text((135+random.randint(-8,8),y),line,font=font,fill=(35,45,55)); y += 100+random.randint(-6,8)
    for _ in range(2500):
        x=random.randrange(w); yy=random.randrange(h); v=random.randint(180,235); draw.point((x,yy),fill=(v,v,v))
    img=img.filter(ImageFilter.GaussianBlur(.25)).rotate(random.uniform(-.6,.6),expand=True,fillcolor=(255,255,255)); img.save(path,'PDF',resolution=150)
def main():
    for f,t,s,table in DIGITAL: digital(PDFS/f,t,s,table)
    for f,t,case,discussion in ARTICLES: article(PDFS/f,t,case,discussion)
    for f,t,s in NON_ENGLISH: digital(PDFS/f,t,s)
    for f,seed,lines in SCANNED: scanned(PDFS/f,seed,lines)
    emails=json.loads((ROOT/'expected'/'email_manifest.json').read_text(encoding='utf-8'))
    for item in emails: (EMAILS/f"{item['id']}.json").write_text(json.dumps(item,indent=2,ensure_ascii=False),encoding='utf-8')
    print(f"generated {len(list(PDFS.glob('*.pdf')))} PDFs and {len(emails)} email fixtures under {OUT}")
if __name__=='__main__': main()

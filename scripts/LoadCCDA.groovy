import grails.plugins.rest.client.RestBuilder
import groovy.xml.MarkupBuilder
import groovy.transform.Field

import java.nio.file.Files
import java.nio.file.Paths

import org.hl7.fhir.model.AtomEntry
import org.hl7.fhir.model.AtomFeed
import org.hl7.fhir.model.Binary
import org.hl7.fhir.model.CodeableConcept
import org.hl7.fhir.model.Coding
import org.hl7.fhir.model.DocumentReference
import org.hl7.fhir.model.Identifier
import org.hl7.fhir.model.ResourceReference

// TODO seriously clean this up, and/or replace it with
// a standalone set of scripts that don't have a grails 
// dependency.  This approach is very far from ideal.
AtomFeed feed = new AtomFeed()

def rest = new RestBuilder(connectTimeout:10000, readTimeout:10000)
Map oauth = [clientId: System.env.CLIENT_ID, clientSecret: System.env.CLIENT_SECRET]

def fhirBase = System.env.BASE_URL
String pid = System.env.PATIENT_ID ?: "123"
String filePath = System.env.CCDA ?: 'grails-app/conf/examples/ccda.xml'
byte[] bytes = Files.readAllBytes(Paths.get(filePath));

println("Posting a new C-CDA to: $fhirBase")
println("Patient ID: " + pid)
println("Local file: " + filePath)

def withAuth =  { Closure toWrap ->
	return {
		auth(oauth.clientId, oauth.clientSecret)
		contentType "text/xml"
		toWrap.delegate = delegate
		toWrap.call()
	}
}

//def patient = rest.get(fhirBase+"/patient/@$pid", withAuth {})



//println(""+ patient.status)
//if (patient.status == 404) {

	def patientWriter = new StringWriter()
	def p = new MarkupBuilder(patientWriter)
	p.Patient(
			'xmlns':grailsApplication.config.fhir.namespaces.f,
			'xmlns:xhtml':grailsApplication.config.fhir.namespaces.xhtml) {
				text() {
					status(value:'generated')
					'xhtml:div'("A BB+ FHIR Sample Patient -- see documentreference elements for data.")
				}
			}
			
	p = patientWriter.toString().decodeFhirXml()

/*	def put = rest.put(fhirBase+"/patient/@$pid?compartments=patient/@$pid",
	withAuth {body patientWriter.toString() })
	assert put.status == 201
}
*/



def x = new XmlParser().parse(filePath)
def f = new groovy.xml.Namespace('http://hl7.org/fhir')
def h = new groovy.xml.Namespace('urn:hl7-org:v3')

DocumentReference doc = new DocumentReference()

def subject = new ResourceReference()
subject.typeSimple = 'Patient'
subject.referenceSimple = "patient/@$pid"

doc.subject = subject

def masterIdentifier = new Identifier()
masterIdentifier.systemSimple = x.id[0].@root
masterIdentifier.keySimple = x.id[0].@extension
masterIdentifier.labelSimple = "Document ID ${masterIdentifier.systemSimple}/${masterIdentifier.keySimple}"
doc.masterIdentifier = masterIdentifier
String patientCompartment = '123'

Map systems = [
	'2.16.840.1.113883.6.1': 'http://loinc.org',
	'2.16.840.1.113883.6.96': 'http://snomed.info/id'
]

def type = new CodeableConcept()
typeCoding = new Coding()
type.coding = [typeCoding]
if (x.code[0]?.@codeSystem) {
	typeCoding.systemSimple = systems[x.code[0].@codeSystem] ?: x.code[0].@codeSystem
	typeCoding.codeSimple = x.code[0].@code
	typeCoding.displaySimple = x.code[0].@displayName
} else {
	typeCoding.displaySimple = "Unknown"
}
doc.type = type

def contained =  []
x.author.assignedAuthor.assignedPerson.each {
	def author = new ResourceReference()
	def name =  it.name.given.collect{it.text()}.join(" ")+ " " +  it.name.family.text()
	author.typeSimple = 'Practitioner'
	author.referenceSimple = '#author-'+contained.size()
	author.displaySimple = name
	doc.author.add(author)

	def prac = """<Practitioner xmlns="http://hl7.org/fhir" id="${author.referenceSimple[1..-1]}">
        <name>
          <family value="${it.name.family.text()}"/>\n""" + 
		  it.name.given.collect {"""<given value="${it.name.given[0].text()}"/>"""}.join("\n") +
        """</name>
	</Practitioner>"""
    //println("prac: " + p)
    doc.contained.add(prac.decodeFhirXml())
}

doc.indexedSimple = Calendar.instance
doc.statusSimple = DocumentReference.DocumentReferenceStatus.current
doc.author.collect{it.displaySimple}

doc.descriptionSimple = x.title.text()
doc.mimeTypeSimple = "application/hl7-v3+xml"

Binary rawResource = new Binary()
rawResource.setContent(bytes)
rawResource.setContentType(doc.mimeTypeSimple)
println "making closure"
println("here oa" + oauth)
/*
 def binary  = rest.post(fhirBase+"/binary?compartments=patient/@$pid", withAuth {
	body rawResource.encodeAsFhirXml()
})
println binary.headers.location[0]
*/
Calendar now = Calendar.instance

AtomEntry patientRef = new AtomEntry()
patientRef.id = "patient/@$pid"
patientRef.resource = p
patientRef.updated = now

AtomEntry rawEntry = new AtomEntry()
rawEntry.id = "urn:cid:binary-document"
rawEntry.resource = rawResource
rawEntry.updated = now


doc.locationSimple = rawEntry.id
AtomEntry rawDocRef = new AtomEntry()
rawDocRef.id = "urn:cid:doc-ref"
rawDocRef.resource = doc
rawDocRef.updated = now

feed.entryList.add(patientRef)
feed.entryList.add(rawEntry)
feed.entryList.add(rawDocRef)

feed.authorName = "groovy.config.atom.author-name"
feed.authorUri  = "groovy.config.atom.author-uri"
feed.id = "feed-id"
feed.updated = now

def docPost  = rest.post(fhirBase+"/?compartments=patient/@$pid",
	withAuth {
		body feed.encodeAsFhirXml()
})
println("Created feed:\n" + docPost.body)

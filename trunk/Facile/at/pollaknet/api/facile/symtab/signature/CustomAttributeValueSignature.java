package at.pollaknet.api.facile.symtab.signature;

import java.util.ArrayList;
import java.util.List;

import at.pollaknet.api.facile.exception.InvalidSignatureException;
import at.pollaknet.api.facile.metamodel.entries.CustomAttributeEntry;
import at.pollaknet.api.facile.metamodel.entries.MemberRefEntry;
import at.pollaknet.api.facile.metamodel.entries.MethodDefEntry;
import at.pollaknet.api.facile.metamodel.entries.TypeRefEntry;
import at.pollaknet.api.facile.metamodel.entries.aggregation.ICustomAttributeType;
import at.pollaknet.api.facile.symtab.BasicTypesDirectory;
import at.pollaknet.api.facile.symtab.TypeInstance;
import at.pollaknet.api.facile.symtab.TypeKind;
import at.pollaknet.api.facile.symtab.symbols.Instance;
import at.pollaknet.api.facile.symtab.symbols.Parameter;
import at.pollaknet.api.facile.util.ByteReader;
import at.pollaknet.api.facile.util.Pair;


public class CustomAttributeValueSignature extends Signature {

	private List<Instance> fixedArguments = new ArrayList<Instance>();
	private List<Pair<String, Instance>> namedFields = new ArrayList<Pair<String, Instance>>();
	private List<Pair<String, Instance>> namedProperties = new ArrayList<Pair<String, Instance>>();
	
	public static CustomAttributeValueSignature decodeAndAttach(BasicTypesDirectory directory, CustomAttributeEntry customAttribute)
			throws InvalidSignatureException {
		return new CustomAttributeValueSignature(directory, customAttribute);
	}

	@SuppressWarnings("unchecked")
	private CustomAttributeValueSignature(BasicTypesDirectory directory, CustomAttributeEntry customAttribute)
			throws InvalidSignatureException {
		
		//See ECMA 335 revision 5 - Partition II, 23.3 Custom attributes
		//                  .---------------.                     .--------------.
		//                  v               |                     v              |
		//  -->[Prolog]--+----->[FixedArg]--+----->[NumNamed]--+---->[NamedArg]--+----->
		//               |                     ^               |                    ^
		//               '---------------------'               '--------------------'
		
		setBinarySignature(customAttribute.getValue());
		setDirectory(directory);
		nextToken();
		
		//read prolog
		prolog();
		
		//handles the FixedArg block and eventual repetitions of it
		fixedArgs(customAttribute);
		
		if(hasNext()) {
			int numNamedArguamtes = ByteReader.getUInt16(binarySignature, currentIndex);
			nextToken();
			nextToken();
	
			for(int i=0;i<numNamedArguamtes;i++) {
				if(!namedArgument(customAttribute)) {
					i=numNamedArguamtes;
				}
			}
		}
		if(fixedArguments.size()>0) {
			Instance [] parameterInstances = new Instance[fixedArguments.size()];
			fixedArguments.toArray(parameterInstances);
			customAttribute.setFixedArguments(parameterInstances);
		}
		if(namedFields.size()>0) {
			Pair<String, Instance> [] namedInstances = new Pair[namedFields.size()];
			namedFields.toArray(namedInstances);
			customAttribute.setNamedFields(namedInstances);
		}
		if(namedProperties.size()>0) {
			Pair<String, Instance> [] namedInstances = new Pair[namedProperties.size()];
			namedProperties.toArray(namedInstances);
			customAttribute.setNamedProperties(namedInstances);
		}

//		for(int i=currentIndex;i<binarySignature.length;i++) {
//			if(binarySignature[i]!=0) {
//				throw new InvalidSignatureException("Unconsumed Tokens detected.");
//			}
//		}
		
	}

	private void prolog() {
		//ensure that the signature contains the custom attribute prolog
		if(currentToken!=PREFIX_CUSTOM_ATTRIBUTE) {
			throw new InvalidSignatureException(currentToken, PREFIX_CUSTOM_ATTRIBUTE);
		}
		nextToken();
		if(currentToken!=0) {
			throw new InvalidSignatureException(currentToken, 0);
		}
		nextToken();
	}

	/*
	 *  FixedArg ::= Elem | StrLenInt32 String
	 *  FixedArgs ::= { FixedArg }
	 *  Number of repetitions depends on the number of fixed arguments of the attach method or member
	 *  This information is stored in the methodDef/memberRef
	 */
	private void fixedArgs(CustomAttributeEntry customAttribute) {
		//get the type of the custom attribute
		ICustomAttributeType customAttributeType = customAttribute.getCustomAttributeType();
		assert(customAttributeType!=null);
		
		//The following code is intended to be used for the unimplemented feature
		//"custom attribute type via string".
		//		if(customAttributeType==null) {
		//			//the custom attribute type is specified by a string (stupid specification!)
		//			assert(customAttribute.getCustomAttributeTypeByString()!=null);
		//			return;
		//		}
		
		//detect kind (weather method or member-ref)
		MethodDefEntry method = customAttributeType.getMethod();

		if(method==null) {
			//in this case the custom attribute is attached to a member
			MemberRefEntry memberRef = customAttributeType.getMemberRef();
			
			if(memberRef==null) {
				//this case is only reached by obsolete attributes
				//TODO: Handle obsolete attributes properly
				assert(false) : "Attached to a type ref!";
			} else {
				//it is a member reference
				
				if(memberRef.getMethodRefSignature()!=null) {	
					for(Parameter p: memberRef.getMethodRefSignature().getParameters()) {
						fixedArguments.add(fixedArgument(customAttribute, p.getTypeRef()));
					}
				}
			}

		} else
			//in this case the custom attribute is attached to a method
			if(method.getMethodSignature()!=null) {
				for(Parameter p:method.getMethodSignature().getParameters()) {
					fixedArguments.add(fixedArgument(customAttribute, p.getTypeRef()));
			}
		}
	}

	private boolean namedArgument(CustomAttributeEntry customAttribute) throws InvalidSignatureException {
		List<Pair<String, Instance>> targetList;

		//a named argument has a defind start signature - check this
		if(currentToken==UNNAMED_CSTM_ATRB_FIELD) {
			targetList = namedFields;
		} else if(currentToken==UNNAMED_CSTM_ATRB_PROPERTY) {
			targetList = namedProperties;
		} else {
			if(malformedSignature) {
				skipTokens(binarySignature.length-currentIndex);
				return false;
			}
			
			throw new InvalidSignatureException(currentToken);
		}
		nextToken();
		
		
		TypeRefEntry fieldOrPropertyTypeRef = null;
		
		if(currentToken==Signature.UNNAMED_BOXED_OBJECT) {
			fieldOrPropertyTypeRef = directory.getType(TypeKind.ELEMENT_TYPE_OBJECT);
			nextToken();
		} else if(currentToken==Signature.UNNAMED_SYSTEM_TYPE) {
			fieldOrPropertyTypeRef = directory.getType(UNNAMED_SYSTEM_TYPE);
			nextToken();
		} else {
			fieldOrPropertyTypeRef = filedOrPropertyType();
		}
		
		//read the name of the argument
		String name = readSerString();
		
		TypeInstance instance = fixedArgument(customAttribute, fieldOrPropertyTypeRef);
		
		targetList.add(new Pair<String, Instance>(name, instance));
		
		return true;
	}

}

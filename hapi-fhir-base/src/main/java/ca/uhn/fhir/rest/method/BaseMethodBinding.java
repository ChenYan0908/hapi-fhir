package ca.uhn.fhir.rest.method;

/*
 * #%L
 * HAPI FHIR Library
 * %%
 * Copyright (C) 2014 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.apache.commons.lang3.StringUtils.*;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.dstu.resource.OperationOutcome;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationSystemEnum;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationTypeEnum;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.annotation.AddTags;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.Delete;
import ca.uhn.fhir.rest.annotation.DeleteTags;
import ca.uhn.fhir.rest.annotation.GetTags;
import ca.uhn.fhir.rest.annotation.History;
import ca.uhn.fhir.rest.annotation.Metadata;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.annotation.Validate;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.BaseClientInvocation;
import ca.uhn.fhir.rest.client.exceptions.NonFhirResponseException;
import ca.uhn.fhir.rest.param.IParameter;
import ca.uhn.fhir.rest.param.ParameterUtil;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.EncodingEnum;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionNotSpecifiedException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.ReflectionUtil;

public abstract class BaseMethodBinding implements IClientResponseHandler {

	private FhirContext myContext;
	private Method myMethod;
	private List<IParameter> myParameters;
	private Object myProvider;

	public BaseMethodBinding(Method theMethod, FhirContext theContext, Object theProvider) {
		assert theMethod != null;
		assert theContext != null;

		myMethod = theMethod;
		myContext = theContext;
		myProvider = theProvider;
		myParameters = ParameterUtil.getResourceParameters(theMethod);
	}

	public FhirContext getContext() {
		return myContext;
	}

	public Method getMethod() {
		return myMethod;
	}

	public Object getProvider() {
		return myProvider;
	}

	/**
	 * Returns the name of the resource this method handles, or
	 * <code>null</code> if this method is not resource specific
	 */
	public abstract String getResourceName();

	public abstract RestfulOperationTypeEnum getResourceOperationType();

	public abstract RestfulOperationSystemEnum getSystemOperationType();

	public abstract BaseClientInvocation invokeClient(Object[] theArgs) throws InternalErrorException;

	public abstract void invokeServer(RestfulServer theServer, Request theRequest, HttpServletResponse theResponse) throws BaseServerResponseException, IOException;

	public abstract boolean incomingServerRequestMatchesMethod(Request theRequest);

	protected IParser createAppropriateParserForParsingResponse(String theResponseMimeType, Reader theResponseReader, int theResponseStatusCode) {
		EncodingEnum encoding = EncodingEnum.forContentType(theResponseMimeType);		
		if (encoding==null) {
			NonFhirResponseException ex = new NonFhirResponseException(theResponseStatusCode, "Response contains non-FHIR content-type: " + theResponseMimeType);
			populateException(ex, theResponseReader);
			throw ex;
		}
		
		IParser parser=encoding.newParser(getContext());
		return parser;
	}

	public List<IParameter> getParameters() {
		return myParameters;
	}

	protected Object invokeServerMethod(Object[] theMethodParams) {
		try {
			Method method = getMethod();
			return method.invoke(getProvider(), theMethodParams);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof BaseServerResponseException) {
				throw (BaseServerResponseException) e.getCause();
			} else {
				throw new InternalErrorException("Failed to call access method", e);
			}
		} catch (Exception e) {
			throw new InternalErrorException("Failed to call access method", e);
		}
	}

	/** For unit tests only */
	public void setParameters(List<IParameter> theParameters) {
		myParameters = theParameters;
	}

	@SuppressWarnings("unchecked")
	public static BaseMethodBinding bindMethod(Method theMethod, FhirContext theContext, Object theProvider) {
		Read read = theMethod.getAnnotation(Read.class);
		Search search = theMethod.getAnnotation(Search.class);
		Metadata conformance = theMethod.getAnnotation(Metadata.class);
		Create create = theMethod.getAnnotation(Create.class);
		Update update = theMethod.getAnnotation(Update.class);
		Delete delete = theMethod.getAnnotation(Delete.class);
		History history = theMethod.getAnnotation(History.class);
		Validate validate = theMethod.getAnnotation(Validate.class);
		GetTags getTags = theMethod.getAnnotation(GetTags.class);
		AddTags addTags = theMethod.getAnnotation(AddTags.class);
		DeleteTags deleteTags = theMethod.getAnnotation(DeleteTags.class);
		// ** if you add another annotation above, also add it to the next line:
		if (!verifyMethodHasZeroOrOneOperationAnnotation(theMethod, read, search, conformance, create, update, delete, history, validate, getTags, addTags, deleteTags)) {
			return null;
		}

		Class<? extends IResource> returnType;

		Class<? extends IResource> returnTypeFromRp = null;
		if (theProvider instanceof IResourceProvider) {
			returnTypeFromRp = ((IResourceProvider) theProvider).getResourceType();
			if (!verifyIsValidResourceReturnType(returnTypeFromRp)) {
				throw new ConfigurationException("getResourceType() from " + IResourceProvider.class.getSimpleName() + " type " + theMethod.getDeclaringClass().getCanonicalName() + " returned " + toLogString(returnTypeFromRp) + " - Must return a resource type");
			}
		}

		Class<?> returnTypeFromMethod = theMethod.getReturnType();
		if (getTags != null) {
			if (!TagList.class.equals(returnTypeFromMethod)) {
				throw new ConfigurationException("Method '" + theMethod.getName() + "' from type " + theMethod.getDeclaringClass().getCanonicalName() + " is annotated with @" + GetTags.class.getSimpleName() + " but does not return type " + TagList.class.getName());
			}
		} else if (MethodOutcome.class.equals(returnTypeFromMethod)) {
			// returns a method outcome
		} else if (Bundle.class.equals(returnTypeFromMethod)) {
			// returns a bundle
		} else if (void.class.equals(returnTypeFromMethod)) {
			// returns a bundle
		} else if (Collection.class.isAssignableFrom(returnTypeFromMethod)) {
			returnTypeFromMethod = ReflectionUtil.getGenericCollectionTypeOfMethodReturnType(theMethod);
			if (!verifyIsValidResourceReturnType(returnTypeFromMethod) && !IResource.class.equals(returnTypeFromMethod)) {
				throw new ConfigurationException("Method '" + theMethod.getName() + "' from " + IResourceProvider.class.getSimpleName() + " type " + theMethod.getDeclaringClass().getCanonicalName() + " returns a collection with generic type " + toLogString(returnTypeFromMethod)
						+ " - Must return a resource type or a collection (List, Set) with a resource type parameter (e.g. List<Patient> )");
			}
		} else {
			if (!IResource.class.equals(returnTypeFromMethod) && !verifyIsValidResourceReturnType(returnTypeFromMethod)) {
				throw new ConfigurationException("Method '" + theMethod.getName() + "' from " + IResourceProvider.class.getSimpleName() + " type " + theMethod.getDeclaringClass().getCanonicalName() + " returns " + toLogString(returnTypeFromMethod)
						+ " - Must return a resource type");
			}
		}

		Class<? extends IResource> returnTypeFromAnnotation = IResource.class;
		if (read != null) {
			returnTypeFromAnnotation = read.type();
		} else if (search != null) {
			returnTypeFromAnnotation = search.type();
		} else if (history != null) {
			returnTypeFromAnnotation = history.type();
		} else if (delete != null) {
			returnTypeFromAnnotation = delete.type();
		} else if (create != null) {
			returnTypeFromAnnotation = create.type();
		} else if (update != null) {
			returnTypeFromAnnotation = update.type();
		} else if (validate != null) {
			returnTypeFromAnnotation = validate.type();
		} else if (getTags != null) {
			returnTypeFromAnnotation = getTags.type();
		} else if (addTags != null) {
			returnTypeFromAnnotation = addTags.type();
		} else if (deleteTags != null) {
			returnTypeFromAnnotation = deleteTags.type();
		}

		if (returnTypeFromRp != null) {
			if (returnTypeFromAnnotation != null && returnTypeFromAnnotation != IResource.class) {
				if (!returnTypeFromRp.isAssignableFrom(returnTypeFromAnnotation)) {
					throw new ConfigurationException("Method '" + theMethod.getName() + "' in type " + theMethod.getDeclaringClass().getCanonicalName() + " returns type " + returnTypeFromMethod.getCanonicalName() + " - Must return " + returnTypeFromRp.getCanonicalName()
							+ " (or a subclass of it) per IResourceProvider contract");
				}
				if (!returnTypeFromRp.isAssignableFrom(returnTypeFromAnnotation)) {
					throw new ConfigurationException("Method '" + theMethod.getName() + "' in type " + theMethod.getDeclaringClass().getCanonicalName() + " claims to return type " + returnTypeFromAnnotation.getCanonicalName() + " per method annotation - Must return "
							+ returnTypeFromRp.getCanonicalName() + " (or a subclass of it) per IResourceProvider contract");
				}
				returnType = returnTypeFromAnnotation;
			} else {
				returnType = returnTypeFromRp;
			}
		} else {
			if (returnTypeFromAnnotation != IResource.class) {
				if (!verifyIsValidResourceReturnType(returnTypeFromAnnotation)) {
					throw new ConfigurationException("Method '" + theMethod.getName() + "' from " + IResourceProvider.class.getSimpleName() + " type " + theMethod.getDeclaringClass().getCanonicalName() + " returns " + toLogString(returnTypeFromAnnotation)
							+ " according to annotation - Must return a resource type");
				}
				returnType = returnTypeFromAnnotation;
			} else {
				returnType = (Class<? extends IResource>) returnTypeFromMethod;
			}
		}

		if (read != null) {
			return new ReadMethodBinding(returnType, theMethod, theContext, theProvider);
		} else if (search != null) {
			String queryName = search.queryName();
			return new SearchMethodBinding(returnType, theMethod, queryName, theContext, theProvider);
		} else if (conformance != null) {
			return new ConformanceMethodBinding(theMethod, theContext, theProvider);
		} else if (create != null) {
			return new CreateMethodBinding(theMethod, theContext, theProvider);
		} else if (update != null) {
			return new UpdateMethodBinding(theMethod, theContext, theProvider);
		} else if (delete != null) {
			return new DeleteMethodBinding(theMethod, theContext, theProvider);
		} else if (history != null) {
			return new HistoryMethodBinding(theMethod, theContext, theProvider);
		} else if (validate != null) {
			return new ValidateMethodBinding(theMethod, theContext, theProvider);
		} else if (getTags != null) {
			return new GetTagsMethodBinding(theMethod, theContext, theProvider, getTags);
		} else if (addTags != null) {
			return new AddTagsMethodBinding(theMethod, theContext, theProvider, addTags);
		} else if (deleteTags != null) {
			return new DeleteTagsMethodBinding(theMethod, theContext, theProvider, deleteTags);
		} else {
			throw new ConfigurationException("Did not detect any FHIR annotations on method '" + theMethod.getName() + "' on type: " + theMethod.getDeclaringClass().getCanonicalName());
		}

		// // each operation name must have a request type annotation and be
		// unique
		// if (null != read) {
		// return rm;
		// }
		//
		// SearchMethodBinding sm = new SearchMethodBinding();
		// if (null != search) {
		// sm.setRequestType(SearchMethodBinding.RequestType.GET);
		// } else if (null != theMethod.getAnnotation(PUT.class)) {
		// sm.setRequestType(SearchMethodBinding.RequestType.PUT);
		// } else if (null != theMethod.getAnnotation(POST.class)) {
		// sm.setRequestType(SearchMethodBinding.RequestType.POST);
		// } else if (null != theMethod.getAnnotation(DELETE.class)) {
		// sm.setRequestType(SearchMethodBinding.RequestType.DELETE);
		// } else {
		// return null;
		// }
		//
		// return sm;
	}

	public static EncodingEnum determineResponseEncoding(Request theReq) {
		String[] format = theReq.getParameters().remove(Constants.PARAM_FORMAT);
		if (format != null) {
			for (String nextFormat : format) {
				EncodingEnum retVal = Constants.FORMAT_VAL_TO_ENCODING.get(nextFormat);
				if (retVal != null) {
					return retVal;
				}
			}
		}

		Enumeration<String> acceptValues = theReq.getServletRequest().getHeaders("Accept");
		if (acceptValues != null) {
			while (acceptValues.hasMoreElements()) {
				EncodingEnum retVal = Constants.FORMAT_VAL_TO_ENCODING.get(acceptValues.nextElement());
				if (retVal != null) {
					return retVal;
				}
			}
		}
		return EncodingEnum.XML;
	}

	protected IParser createAppropriateParserForParsingServerRequest(Request theRequest) {
		String contentTypeHeader = theRequest.getServletRequest().getHeader("content-type");
		EncodingEnum encoding;
		if (isBlank(contentTypeHeader)) {
			encoding = EncodingEnum.XML;
		} else {
			int semicolon = contentTypeHeader.indexOf(';');
			if (semicolon!=-1) {
				contentTypeHeader=contentTypeHeader.substring(0,semicolon);
			}
			encoding = EncodingEnum.forContentType(contentTypeHeader);
		}
		
		if (encoding==null) {
			throw new InvalidRequestException("Request contins non-FHIR conent-type header value: " + contentTypeHeader);
		}
		
		IParser parser=encoding.newParser(getContext());
		return parser;
	}

	public static boolean verifyMethodHasZeroOrOneOperationAnnotation(Method theNextMethod, Object... theAnnotations) {
		Object obj1 = null;
		for (Object object : theAnnotations) {
			if (object != null) {
				if (obj1 == null) {
					obj1 = object;
				} else {
					throw new ConfigurationException("Method " + theNextMethod.getName() + " on type '" + theNextMethod.getDeclaringClass().getSimpleName() + " has annotations @" + obj1.getClass().getSimpleName() + " and @" + object.getClass().getSimpleName()
							+ ". Can not have both.");
				}

			}
		}
		if (obj1 == null) {
			return false;
			// throw new ConfigurationException("Method '" +
			// theNextMethod.getName() + "' on type '" +
			// theNextMethod.getDeclaringClass().getSimpleName() +
			// " has no FHIR method annotations.");
		}
		return true;
	}

	private static String toLogString(Class<?> theType) {
		if (theType == null) {
			return null;
		}
		return theType.getCanonicalName();
	}

	private static boolean verifyIsValidResourceReturnType(Class<?> theReturnType) {
		if (theReturnType == null) {
			return false;
		}
		if (!IResource.class.isAssignableFrom(theReturnType)) {
			return false;
		}
		boolean retVal = Modifier.isAbstract(theReturnType.getModifiers()) == false;
		return retVal;
	}

	protected Object[] createParametersForServerRequest(Request theRequest, IResource theResource) {
		Object[] params = new Object[getParameters().size()];
		for (int i = 0; i < getParameters().size(); i++) {
			IParameter param = getParameters().get(i);
			if (param == null) {
				continue;
			}
			params[i] = param.translateQueryParametersIntoServerArgument(theRequest, theResource);
		}
		return params;
	}

	protected static List<IResource> toResourceList(Object response) throws InternalErrorException {
		if (response == null) {
			return Collections.emptyList();
		} else if (response instanceof IResource) {
			return Collections.singletonList((IResource) response);
		} else if (response instanceof Collection) {
			List<IResource> retVal = new ArrayList<IResource>();
			for (Object next : ((Collection<?>) response)) {
				retVal.add((IResource) next);
			}
			return retVal;
		} else {
			throw new InternalErrorException("Unexpected return type: " + response.getClass().getCanonicalName());
		}
	}

	protected static boolean prettyPrintResponse(Request theRequest) {
		Map<String, String[]> requestParams = theRequest.getParameters();
		String[] pretty = requestParams.remove(Constants.PARAM_PRETTY);
		boolean prettyPrint = false;
		if (pretty != null && pretty.length > 0) {
			if (Constants.PARAM_PRETTY_VALUE_TRUE.equals(pretty[0])) {
				prettyPrint = true;
			}
		}
		return prettyPrint;
	}

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseMethodBinding.class);

	protected BaseServerResponseException processNon2xxResponseAndReturnExceptionToThrow(int theStatusCode, String theResponseMimeType, Reader theResponseReader) {
		BaseServerResponseException ex;
		switch (theStatusCode) {
		case Constants.STATUS_HTTP_400_BAD_REQUEST:
			ex = new InvalidRequestException("Server responded with HTTP 400");
			break;
		case Constants.STATUS_HTTP_404_NOT_FOUND:
			ex = new ResourceNotFoundException("Server responded with HTTP 404");
			break;
		case Constants.STATUS_HTTP_405_METHOD_NOT_ALLOWED:
			ex = new MethodNotAllowedException("Server responded with HTTP 405");
			break;
		case Constants.STATUS_HTTP_409_CONFLICT:
			ex = new ResourceVersionConflictException("Server responded with HTTP 409");
			break;
		case Constants.STATUS_HTTP_412_PRECONDITION_FAILED:
			ex = new ResourceVersionNotSpecifiedException("Server responded with HTTP 412");
			break;
		case Constants.STATUS_HTTP_422_UNPROCESSABLE_ENTITY:
			IParser parser = createAppropriateParserForParsingResponse(theResponseMimeType, theResponseReader, theStatusCode);
			OperationOutcome operationOutcome = parser.parseResource(OperationOutcome.class, theResponseReader);
			ex = new UnprocessableEntityException(operationOutcome);
			break;
		default:
			ex = new UnclassifiedServerFailureException(theStatusCode, "Server responded with HTTP " + theStatusCode);
			break;
		}

		populateException(ex, theResponseReader);
		return ex;
	}

	private static void populateException(BaseServerResponseException theEx, Reader theResponseReader) {
		try {
			String responseText = IOUtils.toString(theResponseReader);
			theEx.setResponseBody(responseText);
		} catch (IOException e) {
			ourLog.debug("Failed to read response", e);
		}
	}

}